package com.example.demo.dto.ml;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.List;

@Getter
public class InferenceRequest {

    @JsonProperty("features")
    private List<Double> features;

    @JsonProperty("model_name")
    private String modelName = "default_nn";

    public InferenceRequest() {}

    public InferenceRequest(List<Double> features, String modelName) {
        this.features = features;
        this.modelName = modelName != null ? modelName : "default_nn";
    }

    public void setFeatures(List<Double> features) {
        this.features = features;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName != null ? modelName : "default_nn";
    }

    public String getModelName() {
        return modelName != null ? modelName : "default_nn";
    }

    public static InferenceRequest of(List<Double> features, String modelName) {
        return new InferenceRequest(features, modelName);
    }
}