package com.example.demo.dto.ml;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
public class InferenceResponse {

    @JsonProperty("prediction")
    private Double prediction;

    @JsonProperty("confidence")
    private Double confidence;

    @JsonProperty("model_name")
    private String modelName;

    @JsonProperty("inference_time_ms")
    private Double inferenceTimeMs;

    @JsonProperty("request_id")
    private String requestId;

    public InferenceResponse() {}

    public void setPrediction(Double prediction) {
        this.prediction = prediction;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public void setInferenceTimeMs(Double inferenceTimeMs) {
        this.inferenceTimeMs = inferenceTimeMs;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}