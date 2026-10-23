import os
import time
import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field, ConfigDict
from typing import List, Optional
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="ML Inference Service",
    description="Simulates neural network inference for hackathon",
    version="1.0.0"
)

class InferenceRequest(BaseModel):
    model_config = ConfigDict(protected_namespaces=())
    features: List[float] = Field(..., description="Input features for inference", min_length=1, max_length=100)
    model_name: Optional[str] = Field(default="default_nn", description="Model identifier")

class InferenceResponse(BaseModel):
    model_config = ConfigDict(protected_namespaces=())
    prediction: float
    confidence: float
    model_name: str
    inference_time_ms: float
    request_id: str

class HealthResponse(BaseModel):
    status: str
    service: str
    version: str

class ModelInfo(BaseModel):
    name: str
    version: str
    input_dim: int
    output_dim: int

# Simulated neural network weights (fixed for reproducibility)
np.random.seed(42)
MODEL_WEIGHTS = {
    "default_nn": {
        "weights": np.random.randn(10, 1).astype(np.float32),
        "bias": np.random.randn(1).astype(np.float32),
        "input_dim": 10,
        "output_dim": 1
    },
    "large_nn": {
        "weights": np.random.randn(50, 1).astype(np.float32),
        "bias": np.random.randn(1).astype(np.float32),
        "input_dim": 50,
        "output_dim": 1
    }
}

def simulate_inference(features: List[float], model_name: str) -> tuple:
    """Simulate neural network forward pass"""
    model = MODEL_WEIGHTS.get(model_name, MODEL_WEIGHTS["default_nn"])
    
    # Pad or truncate features to match model input dimension
    input_dim = model["input_dim"]
    if len(features) < input_dim:
        features = features + [0.0] * (input_dim - len(features))
    elif len(features) > input_dim:
        features = features[:input_dim]
    
    x = np.array(features, dtype=np.float32).reshape(1, -1)
    
    # Simulate forward pass: linear layer + activation
    logits = np.dot(x, model["weights"]) + model["bias"]
    
    # Apply sigmoid for probability-like output
    prediction = float(1 / (1 + np.exp(-logits[0, 0])))
    
    # Simulate confidence based on distance from 0.5
    confidence = float(2 * abs(prediction - 0.5))
    
    return prediction, confidence

@app.get("/health", response_model=HealthResponse)
async def health_check():
    return HealthResponse(
        status="healthy",
        service="ml-inference-service",
        version="1.0.0"
    )

@app.get("/models/{model_name}", response_model=ModelInfo)
async def get_model_info(model_name: str):
    if model_name not in MODEL_WEIGHTS:
        raise HTTPException(status_code=404, detail=f"Model {model_name} not found")
    model = MODEL_WEIGHTS[model_name]
    return ModelInfo(
        name=model_name,
        version="1.0.0",
        input_dim=model["input_dim"],
        output_dim=model["output_dim"]
    )

@app.get("/models", response_model=List[ModelInfo])
async def list_models():
    return [
        ModelInfo(name=name, version="1.0.0", input_dim=info["input_dim"], output_dim=info["output_dim"])
        for name, info in MODEL_WEIGHTS.items()
    ]

@app.post("/inference", response_model=InferenceResponse)
async def inference(request: InferenceRequest):
    start_time = time.perf_counter()
    
    if request.model_name not in MODEL_WEIGHTS:
        raise HTTPException(status_code=400, detail=f"Unknown model: {request.model_name}")
    
    try:
        prediction, confidence = simulate_inference(request.features, request.model_name)
    except Exception as e:
        logger.error(f"Inference error: {e}")
        raise HTTPException(status_code=500, detail="Inference failed")
    
    inference_time = (time.perf_counter() - start_time) * 1000
    request_id = f"req_{int(time.time() * 1000)}"
    
    logger.info(f"Inference completed: model={request.model_name}, prediction={prediction:.4f}, time={inference_time:.2f}ms")
    
    return InferenceResponse(
        prediction=prediction,
        confidence=confidence,
        model_name=request.model_name,
        inference_time_ms=inference_time,
        request_id=request_id
    )

@app.post("/batch-inference")
async def batch_inference(requests: List[InferenceRequest]):
    results = []
    for req in requests:
        if req.model_name not in MODEL_WEIGHTS:
            results.append({"error": f"Unknown model: {req.model_name}"})
            continue
        prediction, confidence = simulate_inference(req.features, req.model_name)
        results.append({
            "prediction": prediction,
            "confidence": confidence,
            "model_name": req.model_name
        })
    return {"results": results}

if __name__ == "__main__":
    import uvicorn
    port = int(os.getenv("PORT", "8000"))
    uvicorn.run(app, host="0.0.0.0", port=port)