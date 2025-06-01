package naclts.cloudimageresizer;

// import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;

public class DependencyFactory {

    public static AmazonS3 s3() {
        AmazonS3 s3 = AmazonS3ClientBuilder.standard()
            .withRegion(Regions.AP_NORTHEAST_1)
            // .withCredentials(new ProfileCredentialsProvider("myProfile"))
            .build();
        return s3;
    }

    public static AmazonSQS sqs() {
        AmazonSQS sqs = AmazonSQSClientBuilder.defaultClient();
        return sqs;
    }

}