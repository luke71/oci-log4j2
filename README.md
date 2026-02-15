# OCI Log4j2 Async Appender

An asynchronous Log4j2 appender that sends logs to **Oracle Cloud Infrastructure Logging Ingestion** using batching, periodic flushing, and backpressure handling.

Open‑source project developed by **Luca Scarpa**.

---

## ✨ Features

- Asynchronous log delivery to OCI Logging Ingestion  
- Configurable batching  
- Periodic flush using a scheduler  
- Supports all Log4j2 layouts (PatternLayout, JSON, etc.)  
- Integration with OCI SDK (ConfigFileAuthenticationDetailsProvider)  
- No log loss under backpressure  
- JUnit 5 test suite  

---

## 🚀 Installation

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.oci.appender</groupId>
    <artifactId>oci-log4j2</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>


Appender configuration example

<Appenders>
    <OciAsyncAppender name="OCI"
                      logId="ocid1.log.oc1..aaaa"
                      configFile="/Users/luke/.oci/config"
                      profile="DEFAULT"
                      batchSize="50"
                      flushIntervalMs="2000">
        <PatternLayout pattern="%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n"/>
    </OciAsyncAppender>
</Appenders>
