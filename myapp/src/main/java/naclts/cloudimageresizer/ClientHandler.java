package naclts.cloudimageresizer;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.services.sqs.model.Message;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ClientHandler {
    private final AmazonS3 s3;
    private final AmazonSQS sqs;
    private final String bucket;
    private final String uploadQueue;
    private final String downloadQueue;

    private static final String UPLOAD_FOLDER = "upload";
    private static final String DOWNLOAD_FOLDER = "download";

    private static final UUID uuid = UUID.randomUUID();

    public ClientHandler() {
        s3 = DependencyFactory.s3();
        sqs = DependencyFactory.sqs();
        bucket = AwsConfig.bucket;
        uploadQueue = AwsConfig.uploadQueue;
        downloadQueue = AwsConfig.downloadQueue;
    }

    public void resizeImages() {

        String[] uploadKeys = putImages(s3, bucket, UPLOAD_FOLDER);

        String uploadQueueUrl = getQueueUrl(sqs, uploadQueue);

        String downloadQueueUrl = getQueueUrl(sqs, downloadQueue);

        sendMessage(sqs, uploadQueueUrl, uploadKeys);

        List<String> resizedKeys = receiveAndDeleteRequiredMessage(sqs, downloadQueueUrl, uploadKeys);

        popResizedImages(s3, bucket, DOWNLOAD_FOLDER, resizedKeys);

        System.out.println("Exiting...");
    }

    private static String[] putImages(AmazonS3 s3, String bucket_name, String folder_path) {
        File folder = new File(folder_path);
        if (!folder.exists()) {
            folder.mkdirs();
        }
        File[] files = folder.listFiles();
        String[] uploadKeys = new String[files.length];
        int i = 0;
        if (files.length != 0) {
            System.out.format("Uploading images in %s to S3 bucket %s...\n", folder_path, bucket_name);
            for (File file : files) {
                if (file.isFile()) {
                    String keyName = uuid.toString() + "_" + file.getName();
                    uploadKeys[i] = keyName;
                    i++;
                    try {
                        s3.putObject(bucket_name, keyName, file);
                    } catch (AmazonServiceException e) {
                        System.err.println(e.getErrorMessage());
                        System.exit(1);
                    }
                    System.out.println("Image uploaded with key: " + keyName);
                }
            }
        } else {
            System.err.println("Please put images that require resizing in the folder '" + folder_path + "'");
            System.exit(1);
        }
        return uploadKeys;
    }

    private static String getQueueUrl(AmazonSQS sqs, String queue_name) {
        return sqs.getQueueUrl(queue_name).getQueueUrl();
    }

    private static void sendMessage(AmazonSQS sqs, String queueUrl, String[] uploadKeys) {
        for (String uploadKey : uploadKeys) {
            SendMessageRequest send_msg_request = new SendMessageRequest()
                    .withQueueUrl(queueUrl)
                    .withMessageBody(uploadKey)
                    .withDelaySeconds(5);
            sqs.sendMessage(send_msg_request);
        }
    }

    private static List<String> receiveAndDeleteRequiredMessage(AmazonSQS sqs, String downloadQueueUrl, String[] uploadKeys) {
        List<String> resizedKeys = new ArrayList<>();

        while (resizedKeys.size() < uploadKeys.length) {

            List<Message> messages = sqs.receiveMessage(downloadQueueUrl).getMessages();

            for (Message message : messages) {
                for (String uploadKey : uploadKeys) {
                    
                    if (message.getBody().startsWith("resized_"+uploadKey)) {
                        sqs.deleteMessage(downloadQueueUrl, message.getReceiptHandle());
                        resizedKeys.add(message.getBody());
                        break;
                    }
                }
            }
        }
        return resizedKeys;
    }

    private static void popResizedImages(AmazonS3 s3, String bucket_name, String folder_path, List<String> resizedKeys) {
        File dir = new File(folder_path);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        for (String resizedKey : resizedKeys) {
            try {
                S3Object o = s3.getObject(bucket_name, resizedKey);
                S3ObjectInputStream s3is = o.getObjectContent();
                String downloadFilePath = folder_path + "/" + resizedKey;
                FileOutputStream fos = new FileOutputStream(new File(downloadFilePath));
                byte[] read_buf = new byte[1024];
                int read_len = 0;
                while ((read_len = s3is.read(read_buf)) > 0) {
                    fos.write(read_buf, 0, read_len);
                }
                s3is.close();
                fos.close();
                System.out.println("Image downloaded to: " + downloadFilePath);
                s3.deleteObject(bucket_name, resizedKey);
            } catch (AmazonServiceException e) {
                System.err.println(e.getErrorMessage());
                System.exit(1);
            } catch (FileNotFoundException e) {
                System.err.println(e.getMessage());
                System.exit(1);
            } catch (IOException e) {
                System.err.println(e.getMessage());
                System.exit(1);
            }
        }
    }
}
