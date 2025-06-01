# CloudImageResizer

*An AWS application for resizing images which clients upload to the cloud*

## Description

The CloudImageResizer is an AWS application implemented with Java. It accepts clients to upload images to a Amazon S3 Bucket for the Amazon EC2 workers to resize the images. In the application, clients and server processes communicate through Amazon SQS.

Detailed steps are illustrated below:

![Steps](https://raw.githubusercontent.com/NaClts/CloudImageResizer/refs/heads/main/steps.png)

0. Server sets up S3 bucket and SQS queues. Then, client and server get the URL of each SQS queue.

1. Client app uploads images to bucket in S3.

2. Client app places the key to each uploaded image as a message in the SQS “inbox” queue.

3. The waiting EC2 instance retrieves messages from the SQS “inbox” queue and get the keys to images.

4. With the keys to images, the EC2 instance downloads the corresponding images from S3 bucket and processes the images inside the instance.

5. After image processing, the EC2 instance uploads the processed images to S3 bucket.

6. The EC2 instance places the key to each processed image in the SQS “outbox” queue.

7. The waiting client app retrieves messages from the SQS “outbox” queue and get the keys to the processed images that it wants.

8. With the key to processed images, the client app downloads the corresponding processed images.

9. Server cleans up AWS resources.

## Code Structure

```
myapp
│   pom.xml
├───download
├───server_download
├───server_upload
├───src
│   └───main
│       └───java
│           └───naclts
│               └───cloudimageresizer
│                       AwsConfig.java
│                       ClientHandler.java
│                       DependencyFactory.java
│                       MainClient.java
│                       MainServer.java
│                       ServerHandler.java
└───upload
        image_1.jpg
        image_2.jpg
        image_3.jpg
        image_4.jpg
        image_5.jpg
```

**pom.xml:** Maven configuration file containing dependencies, plugins, and build settings.
**MainClient.java:** Main class of client application, which will invoke ClientHandler.java.
**MainServer.java:** Main class of server application, which will invoke ServerHandler.java
**ClientHandler.java:** Invoking AWS calls to support client-specific logic.
**ServerHandler.java:** Invoking AWS calls to support server-specific logic.
**DependencyFactory.java:** Building S3 and SQS clients.
**AwsConfig.java:** Configured with names of S3 bucket and SQS queues to be used in client and server.
**upload/:** Used by client. Folder of images which will be uploaded for image processing.
**download/:** Used by client. Folder of processed images downloaded from AWS services.
**server_upload/:** Used by server. Folder of processed images which will be uploaded to S3 bucket.
**server_download/:** Used by server. Folder of images downloaded from S3 bucket for image processing.

## Installation / Compilation

1. Create EC2 instance(s) of Amazon Linux with Corretto & Maven

2. Clone this repository to the EC2 instance(s)

3. Change directory to `myapp` and complie the application in each EC2 instance:
```mvn clean install```

4. Clone this repository to client machine(s)

5. Change directory to `myapp` and complie the application in each client machine:
```mvn clean install```

## Execution

1. Run main server program in EC2 instance(s):
```mvn exec:java -Dexec.mainClass="naclts.cloudimageresizer.MainServer"```

2. Put original images in `upload/` folder in client machine(s)

3. Run main client program in client machine(s):
```mvn exec:java -Dexec.mainClass="naclts.cloudimageresizer.MainClient"```

4. Get the resized images in `download/` folder in client machine(s)

## Remarks

This project is submitted as HKU COMP3358 Course Assignment.
