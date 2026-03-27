FROM ollama/ollama:latest

# Set variables to ensure it uses CPU and skips GPU checks during the "bake"
ENV OLLAMA_SKIP_GPU_CHECK=true
ENV OLLAMA_HOST=0.0.0.0

# Start ollama, wait for it to be ready, pull the model, then kill the process
RUN ollama serve & \
    sleep 5 && \
    ollama pull llama3 && \
    pkill ollama

EXPOSE 11434
ENTRYPOINT ["ollama", "serve"]