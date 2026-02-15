/*
 * Copyright 2026 Luca Scarpa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oci.appender;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.oracle.bmc.loggingingestion.responses.PutLogsResponse;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;

public class OciAsyncAppenderTest {

    private long flushIntervalMs=3000L; 

    Layout<? extends Serializable> layout = PatternLayout.newBuilder()
                                    .withPattern("%d{HH:mm:ss.SSS} %-5level %logger{1} - %msg%n")
                                    .withCharset(StandardCharsets.UTF_8)
                                    .build();


    private Logger getLogger(OciAsyncAppender appender) {
        // Context di Log4j2
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration config = context.getConfiguration();

        appender.start();

        LoggerConfig testConfig = new LoggerConfig("testLogger", Level.ALL, true); 
        testConfig.addAppender(appender, Level.ALL, null); 
        config.addLogger("testLogger", testConfig); 
        context.updateLoggers();
        
        return LogManager.getLogger("testLogger");
    }

    private OciAsyncAppender getOciAsyncAppender(String name) {
        // Creazione dell'appender (adatta i parametri al tuo costruttore)
        OciAsyncAppender appender = new OciAsyncAppender(
                        name, 
                        null, //log filter
                        layout, //log layout
                        false, //if true do not propagate appender exceptions
                        10,  //Rows at a time
                        flushIntervalMs //flush interval (msec)
                    );
        return appender;
    }

    @Test
    void testAppenderReceivesLogEvent() throws Exception {
        String message = "Test message";
        OciAsyncAppender appender = getOciAsyncAppender("OciAsyncAppenderTest");
        Logger logger=getLogger(appender);
        logger.info(message);
        //Beafore flush
        Assertions.assertEquals(1, appender.getQueueSize()); 
        Thread.sleep(flushIntervalMs+500L);
        //After flush
        Assertions.assertEquals(0, appender.getQueueSize()); 
    }

    @Test
    void testRetryBackoff()  throws Exception {

        OciLoggingClient mockClient = Mockito.mock(OciLoggingClient.class); 
        
        // Prima chiamata: eccezione
        Mockito.when(mockClient.putLogs(Mockito.any()))
                .thenThrow(new RuntimeException("OCI down"))
                .thenReturn(PutLogsResponse.builder().build());
        OciAsyncAppender appender = getOciAsyncAppender("OciAsyncAppenderTest2");        
        appender.setClientForTest(mockClient);
        appender.start();

        // Aggiungiamo un evento
        appender.append(Log4jLogEvent.newBuilder()
                .setMessage(new SimpleMessage("Retry me"))
                .build());

        //Beafore flush
        Assertions.assertEquals(1, appender.getQueueSize()); 
        Thread.sleep(flushIntervalMs*2+500L);

        // Verifica: 2 tentativi
        Mockito.verify(mockClient, Mockito.times(2)).putLogs(Mockito.any());
    }
}
