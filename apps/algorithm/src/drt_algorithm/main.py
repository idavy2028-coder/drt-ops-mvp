from fastapi import FastAPI

from .insertion import evaluate_dispatch
from .schemas import DispatchEvaluateRequest, DispatchEvaluateResponse

app = FastAPI(title="DRT Dispatch Algorithm Service", version="0.1.0")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.post("/dispatch/evaluate", response_model=DispatchEvaluateResponse)
def dispatch_evaluate(request: DispatchEvaluateRequest) -> DispatchEvaluateResponse:
    return evaluate_dispatch(request)
