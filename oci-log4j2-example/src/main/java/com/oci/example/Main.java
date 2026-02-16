package com.oci.example;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;

public class Main {

    static { 
        System.setProperty("log4j.configurationFile", Main.class.getClassLoader().getResource("log4j2.xml").toString()); 
    }

    private static final Logger logger = LogManager.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        System.out.println("MAIN TEST STARTED RUNNING ON"+System.getProperty("java.version"));

        logger.info("Starting OCI Log4j2 example app... with log4j.configurationFile:"+System.getProperty("log4j.configurationFile"));

        for (int i = 1; i <= 5; i++) {
            logger.info("Log message #" + i);
            Thread.sleep(500);
        }

        logger.info("Done. Waiting for flush...");

        // Attendi il flush asincrono 2000 su log4j2.xml
        Thread.sleep(3000);

        logger.info("Application finished.");

        //Mandatory to stop appender scheduler
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false); 
        ctx.getConfiguration().getAppenders().forEach((name, app) -> { System.out.println("Appender registrato: " + name + " -> " + app.getClass()); });
        ctx.stop();

        System.out.println("MAIN TEST FINISHED");
    }
}
