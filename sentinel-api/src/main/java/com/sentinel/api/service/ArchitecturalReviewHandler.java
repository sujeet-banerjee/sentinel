package com.sentinel.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import com.sentinel.api.model.ReviewRequest;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

/**
 * 
 * <pre>
 * Technically, a WebSocketHandler is a piece of infrastructure logic, but we 
 * annotate it with @Service. Reasons?
 * 
 * 1. By making it a @Service, Spring manages its lifecycle. Tomorrow, when we 
 * need to call an AI Analysis Service or a Database Service, we can simply 
 * @Autowire them into this handler.
 * 
 * 2. Marking it as a @Service tells Spring: "This is a functional component 
 * of my business logic." Separation of concern: the WebSocketConfig to only 
 * handle the "Wiring" or "Routing" (which URL goes where).
 * 
 * 3. Cross-cutting concerns without a sprawl: Spring’s @Service annotation 
 * makes it easy to wrap the class in a "Proxy" to handle those cross-cutting 
 * concerns without cluttering your code.
 * </pre>
 */
@Service
public class ArchitecturalReviewHandler implements WebSocketHandler {
	
	private static final Logger log = LoggerFactory.getLogger(
			ArchitecturalReviewHandler.class);
	
	// Constructor Injection: Spring finds the @Service AnalysisService and plugs it in here.
    private final AnalysisService analysisService;
    private final ObjectMapper objectMapper;
    
    public ArchitecturalReviewHandler(AnalysisService analysisService, ObjectMapper objectMapper) {
        this.analysisService = analysisService;
        this.objectMapper = objectMapper;
    }

	/**
	 * <pre>
	 * In a standard REST controller, you return String or UserDTO because the request-response 
	 * cycle is a "one-and-done" transaction. But a WebSocket is a long-lived connection. The 
	 * "Agreement": The Mono<Void> doesn't represent a "value" (like a string). It represents 
	 * the lifecycle of the connection itself.
	 * 
	 * Completion: As long as the Mono is "active," the WebSocket stays open. When the Mono 
	 * completes (finishes), the connection closes. 
	 * 
	 * The Pipeline: By returning session.send(...), you are handing Spring a reactive pipeline.
	 * Spring says: "I will keep this pipe open as long as there is data flowing through this 
	 * 'send' or 'receive' flux." If you returned Mono<String>, the connection would close as soon 
	 * as the first string was sent. 
	 * 
	 * Mono<Void> allows for an infinite stream of architectural critiques.
	 * </pre>
	 */
    @Override
    public Mono<Void> handle(WebSocketSession session) {
    	log.info("New WebSocket connection established: {}", session.getId());
    	
        /*
         * session.receive() -> Listens for incoming messages from the client.
         * session.map() -> Processes the message (currently just an echo).
         * session.textMessage() -> Wraps the string back into a WebSocket frame.
         * session.send() -> Ships the stream back to the client.
         * 
         * The return type of session.send() is Mono<Void>, which signifies 
         * the completion of the "send" operation.
         * 
         * For now, just echoing the message back!
         */
    	return session.send(
                session.receive()
                	// CRITICAL: Tells the server "Only expect one message, then close"
                	//.take(1) 
                	// Just to log
                    .doOnNext(msg -> log.debug("INBOUND: {}", msg.getPayloadAsText()))
                    // msg (WebSocketMessage) to ReviewRequest record (deser)
                    .map(msg -> {
                        try {
                            // Attempt to parse JSON into our ReviewRequest record
                            return objectMapper.readValue(msg.getPayloadAsText(), ReviewRequest.class);
                        } catch (Exception e) {
                            // Fallback: If they send plain text, we wrap it manually
                            log.warn("Invalid JSON received, using fallback wrapper");
                            return new ReviewRequest(msg.getPayloadAsText(), "General", 3);
                        }
                    })
                    /*
                     * flatMap is the key here. It takes one ReviewRequest and 
                     * "flattens" the Flux<String> from the Analysis-service into 
                     * the main outbound stream.
                     */
                    .flatMap(request -> analysisService.analyze(request))
                    .map(chunk -> {
                    	try {
                            String jsonResponse = objectMapper.writeValueAsString(chunk);
                            log.info("ENRICHED STREAMING: {}", jsonResponse);
                            return session.textMessage(jsonResponse);
                        } catch (Exception e) {
                            log.error("Failed to serialize SentinelChunk", e);
                            return session.textMessage(
                            		"{\"content\":\"Error serializing response\","
                            		+ "\"type\":\"TEXT\"}");
                        }
                    })
                    // Logging complete event!
                    .doOnComplete(() -> log.info("Analysis Service Finished"))
                    
            ).doOnTerminate(
        		() -> log.info("Connection closed for session: {}", session.getId())
        	).then();
    }
}