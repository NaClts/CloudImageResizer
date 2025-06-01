package naclts.cloudimageresizer;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.AmazonSQSException;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.services.sqs.model.Message;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ServerHandler {
    private final AmazonS3 s3;
    private final AmazonSQS sqs;
    private final String bucket;
    private final String uploadQueue;
    private final String downloadQueue;

    private String uploadQueueUrl;
    private String downloadQueueUrl;

    private static final String UPLOAD_FOLDER = "server_upload";
    private static final String DOWNLOAD_FOLDER = "server_download";

    public ServerHandler() {
        s3 = DependencyFactory.s3();
        sqs = DependencyFactory.sqs();
        bucket = AwsConfig.bucket;
        uploadQueue = AwsConfig.uploadQueue;
        downloadQueue = AwsConfig.downloadQueue;
    }

    public void setUp() {

        createBucket(s3, bucket);

        createQueue(sqs, uploadQueue);

        uploadQueueUrl = getQueueUrl(sqs, uploadQueue);

        createQueue(sqs, downloadQueue);

        downloadQueueUrl = getQueueUrl(sqs, downloadQueue);

    }

    public void resizeImages() {

        List<String> keys = receiveAndDeleteMessage(sqs, uploadQueueUrl);

        popImages(s3, bucket, DOWNLOAD_FOLDER, keys);

        mogrify(DOWNLOAD_FOLDER, UPLOAD_FOLDER, "800x600");

        String[] resizedKeys = putResizedImages(s3, bucket, UPLOAD_FOLDER);

        sendMessage(sqs, downloadQueueUrl, resizedKeys);

    }

    public void cleanUp() {
        cleanUp(s3, bucket, sqs, uploadQueueUrl, downloadQueueUrl);
    }

    private static Bucket getBucket(AmazonS3 s3, String bucket_name) {
        Bucket named_bucket = null;
        List<Bucket> buckets = s3.listBuckets();
        for (Bucket b : buckets) {
            if (b.getName().equals(bucket_name)) {
                named_bucket = b;
            }
        }
        return named_bucket;
    }

    private static Bucket createBucket(AmazonS3 s3, String bucket_name) {
        Bucket b = null;
        if (s3.doesBucketExistV2(bucket_name)) {
            System.out.format("Bucket %s already exists.\n", bucket_name);
            b = getBucket(s3, bucket_name);
        } else {
            try {
                b = s3.createBucket(bucket_name);
                System.out.println("Bucket created: " + bucket_name);
            } catch (AmazonS3Exception e) {
                System.err.println(e.getErrorMessage());
            }
        }
        return b;
    }

    private static void createQueue(AmazonSQS sqs, String queue_name) {
        CreateQueueRequest create_request = new CreateQueueRequest(queue_name)
                .addAttributesEntry("DelaySeconds", "60")
                .addAttributesEntry("MessageRetentionPeriod", "86400");
        try {
            sqs.createQueue(create_request);
            System.out.println("Queue created: " + queue_name);
        } catch (AmazonSQSException e) {
            if (!e.getErrorCode().equals("QueueAlreadyExists")) {
                throw e;
            }
        }
    }

    private static String getQueueUrl(AmazonSQS sqs, String queue_name) {
        return sqs.getQueueUrl(queue_name).getQueueUrl();
    }

    private static List<String> receiveAndDeleteMessage(AmazonSQS sqs, String uploadQueueUrl) {

        System.out.println("Waiting for message of request of resizing...");
        List<String> stringifiedMessages = new ArrayList<>();
        List<Message> messages = sqs.receiveMessage(uploadQueueUrl).getMessages();
            
        while (stringifiedMessages.isEmpty()) {
            while (messages.isEmpty()) {
                messages = sqs.receiveMessage(uploadQueueUrl).getMessages();
            }
            for (Message m : messages) {
                if ( ! m.getBody().startsWith("resized_") ) {
                    stringifiedMessages.add(m.getBody());
                    sqs.deleteMessage(uploadQueueUrl, m.getReceiptHandle());
                }
            }
        }
        System.out.println("Received " + stringifiedMessages.size() + " request of resizing...");
        return stringifiedMessages;
    }

    private static void popImages(AmazonS3 s3, String bucketName, String downloadFolderPath, List<String> keys) {
        File dir = new File(downloadFolderPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        for (String key : keys) {
            try {
                S3Object o = s3.getObject(bucketName, key);
                S3ObjectInputStream s3is = o.getObjectContent();
                String downloadFilePath = downloadFolderPath + "/" + key;
                FileOutputStream fos = new FileOutputStream(new File(downloadFilePath));
                byte[] read_buf = new byte[1024];
                int read_len = 0;
                while ((read_len = s3is.read(read_buf)) > 0) {
                    fos.write(read_buf, 0, read_len);
                }
                s3is.close();
                fos.close();
                System.out.println("Image downloaded to: " + downloadFilePath);
                s3.deleteObject(bucketName, key);
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

    private static void mogrify(String inputFolder, String outputFolder, String size) {
        try {
            // Create output directory if it doesn't exist
            File outDir = new File(outputFolder);
            if (!outDir.exists()) {
                outDir.mkdirs();
            }

            // Build the mogrify command
            ProcessBuilder pb = new ProcessBuilder(
                "mogrify",
                "-path", outputFolder,
                "-resize", size,
                inputFolder + File.separator + "*"
            );

            // Start the process
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                System.out.println("Images resized successfully.");
            } else {
                System.err.println("Error resizing images. Exit code: " + exitCode);
            }

            File folder = new File(inputFolder);
            File[] files = folder.listFiles();
            for (File file : files) {
                if (file.isFile()) {
                    file.delete();
                }
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static String[] putResizedImages(AmazonS3 s3, String bucket_name, String folder_path) {
        System.out.format("Uploading images in %s to S3 bucket %s...\n", folder_path, bucket_name);
        File folder = new File(folder_path);
        File[] files = folder.listFiles();
        String[] resizedKeys = new String[files.length];
        int i = 0;
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    String keyName = "resized_" + file.getName();
                    resizedKeys[i] = keyName;
                    i++;
                    try {
                        s3.putObject(bucket_name, keyName, file);
                    } catch (AmazonServiceException e) {
                        System.err.println(e.getErrorMessage());
                        System.exit(1);
                    }
                    System.out.println("Image uploaded with key: " + keyName);
                    file.delete();
                }
            }
        }
        return resizedKeys;
    }

    private static void sendMessage(AmazonSQS sqs, String queueUrl, String[] keys) {
        for (String key : keys) {
            SendMessageRequest send_msg_request = new SendMessageRequest()
                    .withQueueUrl(queueUrl)
                    .withMessageBody(key)
                    .withDelaySeconds(5);
            sqs.sendMessage(send_msg_request);
        }
    }

    private static void cleanUp(AmazonS3 s3, String bucket_name, AmazonSQS sqs, String uploadQueueUrl, String downloadQueueUrl) {
        System.out.println("Deleting S3 bucket: " + bucket_name);
        try {
            System.out.println(" - removing objects from bucket");
            ObjectListing object_listing = s3.listObjects(bucket_name);
            while (true) {
                for (Iterator<?> iterator =
                    object_listing.getObjectSummaries().iterator();
                    iterator.hasNext(); ) {
                    S3ObjectSummary summary = (S3ObjectSummary) iterator.next();
                    s3.deleteObject(bucket_name, summary.getKey());
                }

                // more object_listing to retrieve?
                if (object_listing.isTruncated()) {
                    object_listing = s3.listNextBatchOfObjects(object_listing);
                } else {
                    break;
                }
            }

            // System.out.println(" - removing versions from bucket");
            // VersionListing version_listing = s3.listVersions(
            //         new ListVersionsRequest().withBucketName(bucket_name));
            // while (true) {
            //     for (Iterator<?> iterator = version_listing.getVersionSummaries().iterator(); iterator.hasNext();) {
            //         S3VersionSummary vs = (S3VersionSummary) iterator.next();
            //         s3.deleteVersion(
            //                 bucket_name, vs.getKey(), vs.getVersionId());
            //     }

            //     if (version_listing.isTruncated()) {
            //         version_listing = s3.listNextBatchOfVersions(
            //                 version_listing);
            //     } else {
            //         break;
            //     }
            // }

            System.out.println(" OK, bucket ready to delete!");
            s3.deleteBucket(bucket_name);
            System.out.println(bucket_name + " has been deleted.");
            // System.out.printf("%n");
        } catch (AmazonServiceException e) {
            System.err.println(e.getErrorMessage());
            // System.exit(1);
        }

        sqs.deleteQueue(uploadQueueUrl);
        System.out.println("Queue deleted: " + uploadQueueUrl);
        sqs.deleteQueue(downloadQueueUrl);
        System.out.println("Queue deleted: " + downloadQueueUrl);

        System.out.println("Cleanup complete");
        System.out.printf("%n");
    }
}
