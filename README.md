# sentinel



## sentinel-api

This is springboot based API layer.



### Dev CLI

#### Installation

* NodeJs wscat: ``npm install -g wscat``
* PS K6: ``winget install GrafanaLabs.k6``

#### Standalone / Manual

* Run the backend/API-layer (Windows PS):
 ``./mvnw spring-boot:run``
* Run the Websocket client: ``wscat -H "X-Sentinel-Tenant-ID: CLI_HACKER_CORP" -c ws://localhost:8080/ws/analyze``
>>> {"content": "I am testing my persistent Redis memory.", "focusArea": "General", "userId": 1}
---


#### Unit Tests
* Run all tests:
mvnw clean test

* Run specific test:
./mvnw test -Dtest=TestClassName#testMethodName

#### Load Test
* Using K6 (JS):
``k6 run src/test/js/load-test.js``

#### CI Status
[![Sentinel API CI](https://github.com/sujeet-banerjee/sentinel/actions/workflows/maven.yml/badge.svg)](https://github.com/sujeet-banerjee/sentinel/actions/workflows/maven.yml)

