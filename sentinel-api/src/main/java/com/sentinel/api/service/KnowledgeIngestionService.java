package com.sentinel.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);
    private final VectorStore vectorStore;

    public KnowledgeIngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void ingestDocument(Resource resource, Map<String, Object> metadata) {
        log.info("Starting Enterprise Knowledge Ingestion for source: {}", metadata);

        TextReader textReader = new TextReader(resource);
        textReader.getCustomMetadata().putAll(metadata);
        List<Document> rawDocuments = textReader.get();
        
        TokenTextSplitter splitter = new TokenTextSplitter();
        List<Document> chunkedDocuments = splitter.apply(rawDocuments);
        
        log.info("Generating embeddings and writing to PostgreSQL vector store...");
        vectorStore.accept(chunkedDocuments); 
        
        log.info("Knowledge Ingestion Complete for: {}", metadata);
    }
}