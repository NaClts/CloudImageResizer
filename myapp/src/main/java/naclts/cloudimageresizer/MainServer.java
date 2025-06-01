package naclts.cloudimageresizer;

public class MainServer 
{
    public static void main( String[] args )
    {
        ServerHandler serverHandler = new ServerHandler();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Ctrl+C detected! Executing cleanup code...");
            serverHandler.cleanUp();
        }));
        System.out.println("Application is running. Press Ctrl+C to exit.");

        serverHandler.setUp();
        while (true) {
            serverHandler.resizeImages();
        }
    }
}
