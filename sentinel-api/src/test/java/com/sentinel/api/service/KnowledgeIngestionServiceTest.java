package com.sentinel.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;

@Testcontainers
@SpringBootTest
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class KnowledgeIngestionServiceTest {
	private static final Logger log = LoggerFactory.getLogger(
			KnowledgeIngestionServiceTest.class);
	
	@SuppressWarnings("resource")
	@Container
    static final GenericContainer<?> redis = new GenericContainer<>(
    		DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @SuppressWarnings("resource")
	@Container
    static final GenericContainer<?> postgres = new GenericContainer<>(
    		DockerImageName.parse("pgvector/pgvector:pg16"))
            .withEnv("POSTGRES_USER", "sentinel")
            .withEnv("POSTGRES_PASSWORD", "password")
            .withEnv("POSTGRES_DB", "sentinel_db")
            .withExposedPorts(5432);
    
    
    @SuppressWarnings({ "rawtypes", "resource" })
    @Container
    static final GenericContainer ollama = new GenericContainer<>(
            DockerImageName.parse("ghcr.io/sujeet-banerjee/sentinel/sentinel-ollama-llama3:latest")
        )
        .withExposedPorts(11434)
        .withEnv("OLLAMA_SKIP_GPU_CHECK", "true")
        .withReuse(true)
        .waitingFor(Wait.forHttp("/").forStatusCode(200))
        .withStartupTimeout(Duration.ofMinutes(20));
    
    // We auto-wire the vector store to query Postgres directly
    @Autowired
    private VectorStore vectorStore;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
    	log.info("Test Ollama Container started: {} --> {}",
    			ollama.getContainerId(),
    			ollama.getContainerName());
    	
    	log.info("Test Postgres Container started (Already): {} --> {}", 
    			postgres.getContainerId(), 
    			postgres.getContainerName());
    	
    	log.info("Test Redis Container started (Already): {} --> {}", 
    			redis.getContainerId(), 
    			redis.getContainerName());
    	
    	/*
    	 * WE DON'T NEED .start() on the containers:
    	 * --------------------
    	 * When you put @Testcontainers on the class, you are activating a JUnit 5 extension.
    	 * This extension scans your file before the test begins, looks for any field
    	 * annotated with @Container, and automatically calls .start() on them before
    	 * Spring Boot even attempts to load the Application Context.
    	 * 
    	 * Because the fields are static, JUnit knows to start them exactly once before
    	 * the entire test suite begins, rather than restarting them for every single @Test
    	 * method.
    	 * 
    	 * Also, Test-containers is highly fault-tolerant. The .start() method is idempotent
    	 * (meaning it is safe to call multiple times)
    	 */
    	
    	// Redis
    	registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
    	
    	// Postgres mapping
        registry.add("spring.datasource.url", () -> 
        	"jdbc:postgresql://" + postgres.getHost() 
        	+ ":" + postgres.getMappedPort(5432) + "/sentinel_db");
        
        // Ollama dynamic port mapping (Replaces localhost)
        registry.add("spring.ai.ollama.base-url", () -> "http://" + ollama.getHost() 
        	+ ":" + ollama.getMappedPort(11434));
        registry.add("spring.ai.ollama.chat.options.timeout", () -> "30m");
        registry.add("spring.codec.max-in-memory-size", () -> "10MB");
        
        
    }

    @Autowired
    private KnowledgeIngestionService ingestionService;

    @Value("classpath:docs/acme-architecture-guidelines.txt")
    private Resource testDocument;
    
    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @BeforeAll
    static void pullEmbeddingModel() throws Exception {
        /* Our custom GHCR image only now has:
         * - llama3
         * - nomic embedder
         */
//        log.info("Pulling nomic-embed-text model into Ollama container...");
//        log.info("This will take ~10-20 seconds on the very first run, and 0 "
//        		+ "seconds on future runs.");
//        
//        ollama.execInContainer("ollama", "pull", "nomic-embed-text");
        
        log.info("Embedding model is ready!");
    }

    @Test
    @Order(1)
    void shouldIngestDocumentIntoVectorStore() {
    	// ====================================================================
        // 1. ACT: Perform the Ingestion
        // ====================================================================
        // Create a rich, semantic metadata payload
        Map<String, Object> documentMetadata = Map.of(
            "tenant_id", "ACME_CORP",
            "doc_id", "SEC-909",
            "category", "ARCHITECTURE_GUIDELINES",
            "confidentiality", "STRICT"
        );
        ingestionService.ingestDocument(testDocument, documentMetadata);

        // ====================================================================
        // 2. ASSERT: Perform a Semantic Search
        // ====================================================================
        log.info("--- STARTING VECTOR DATABASE VERIFICATION ---");
        
        /*
         * We ask a question that requires semantic matching, 
         * not just a keyword exact match.
         * The document says "MongoDB is strictly prohibited for 
         * transactional financial records."
         */
        String question = "Are we allowed to use MongoDB for financial data?";

        // Build a search request to find the top 2 closest vectors mathematically
        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(2)
                /*
                 * TODO add: .filterExpression("tenant_id == 'ACME_CORP'")
                 */
                .build();

        // Under the hood: This asks Ollama to convert our question into a vector, 
        // then runs a Cosine Distance SQL query against PostgreSQL.
        List<Document> results = vectorStore.similaritySearch(searchRequest);

        // ====================================================================
        // 3. VERIFY: Did it retrieve the correct chunks?
        // ====================================================================
        assertThat(results).isNotEmpty();

        Document topMatch = results.get(0);
        log.info("Top semantic match found:\n{}", topMatch.getText());

        // Verify the text content actually contains the policy we asked about
        assertThat(topMatch.getText()).containsIgnoringCase(
        		"MongoDB is strictly prohibited");
        
        // Verify our rich metadata payload survived the database round trip
        assertThat(topMatch.getMetadata())
            .containsEntry("tenant_id", "ACME_CORP")
            .containsEntry("category", "ARCHITECTURE_GUIDELINES")
            .containsEntry("confidentiality", "STRICT");
    }
    
    @Test
    @Order(2)
    void retrieveAndVerifyVectors() {
        log.info("--- STEP 2: RETRIEVAL & OBSERVABILITY ---");
        String question = "Are we allowed to use MongoDB for financial data?";

        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(2)
                .build();

        List<Document> results = vectorStore.similaritySearch(searchRequest);

        assertThat(results).isNotEmpty();
        Document topMatch = results.get(0);
        
        assertThat(topMatch.getText()).containsIgnoringCase("MongoDB is strictly prohibited");
        assertThat(topMatch.getMetadata()).containsEntry("tenant_id", "ACME_CORP");

        // Optional: Keep your JDBC / Vector Logging logic here too!
    }
}