# OCI Log4j2 Async Appender

[![Maven Central](https://img.shields.io/maven-central/v/com.oci.appender/oci-log4j2.svg?label=Maven%20Central)](https://search.maven.org/artifact/com.oci.appender/oci-log4j2)


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
    <version>1.0.1</version>
</dependency>

---
Configuration examples

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

Oci Config File Example, if your code runs in an OCI VM or OKE you don't need this: sdk obtains automatically the credentials (Instance principal)

[DEFAULT]
user=ocid1.user.oc1...
fingerprint=xx:xx:xx
key_file=/path/to/oci_api_key.pem
tenancy=ocid1.tenancy.oc1...
region=eu-frankfurt-1



## Known Issue: UTF-8 Utility Thread in OCI Java SDK ≥ 3.66.0

Starting from version **3.66.0** of the OCI Java SDK, Jersey introduces an internal
non-daemon thread named **`utf8-utils-*`**. This thread is created by the underlying
HTTP/Jersey infrastructure and **cannot be terminated through any public API** provided
by the SDK.

When running applications via the **`maven-exec-plugin`**, this thread may appear as
“pending” during JVM shutdown. This is a cosmetic warning specific to the Maven plugin
and **does not affect the actual shutdown or behavior of the application**.

Version **3.65.1** is the last release of the OCI Java SDK that does not exhibit this
behavior.
