import base64
import os
from io import BytesIO
from pathlib import Path

import cv2
import numpy as np
from fastapi import FastAPI, UploadFile, File, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from PIL import Image
from starlette.responses import StreamingResponse
from ultralytics import YOLO

ROOT = Path(__file__).resolve().parent

# 推理前缩放到最长边不超过该值，兼顾速度与精度（手机原图常 3000px+）
MAX_INFER_SIZE = int(os.environ.get('YOLO_MAX_SIZE', '1280'))
INFER_IMGSZ = int(os.environ.get('YOLO_IMGSZ', '640'))
RESULT_JPEG_QUALITY = int(os.environ.get('YOLO_JPEG_QUALITY', '82'))

MODEL_CANDIDATES = [
    ROOT / 'models' / 'production' / 'best.pt',
    ROOT / 'runs' / 'segment' / 'train' / 'weights' / 'best.pt',
    ROOT / 'runs' / 'segment' / 'optimizer_AdamW_patience_50' / 'weights' / 'best.pt',
    ROOT / 'models' / 'yolov8s-seg.pt',
    ROOT / 'yolov8n.pt',
]


def resolve_model_path() -> Path:
    env_path = os.environ.get('YOLO_MODEL_PATH', '').strip()
    if env_path:
        p = Path(env_path)
        if p.exists():
            return p
        raise FileNotFoundError(f'YOLO_MODEL_PATH 不存在: {p}')
    for candidate in MODEL_CANDIDATES:
        if candidate.exists():
            return candidate
    raise FileNotFoundError(
        '未找到 YOLO 权重文件，请确认 models/production/best.pt 存在，或设置环境变量 YOLO_MODEL_PATH'
    )


MODEL_PATH = resolve_model_path()
model = YOLO(str(MODEL_PATH))

app = FastAPI(title='Mobile Phone Defect Detection YOLOv8')
app.add_middleware(
    CORSMiddleware,
    allow_origins=['*'],
    allow_methods=['*'],
    allow_headers=['*'],
)

CLASS_NAMES = {0: 'Scratched', 1: 'Scratched'}


def load_image(raw: bytes) -> Image.Image:
    img = Image.open(BytesIO(raw))
    if img.mode != 'RGB':
        img = img.convert('RGB')
    return resize_for_infer(img)


def resize_for_infer(img: Image.Image) -> Image.Image:
    w, h = img.size
    longest = max(w, h)
    if longest <= MAX_INFER_SIZE:
        return img
    scale = MAX_INFER_SIZE / float(longest)
    new_w = max(1, int(w * scale))
    new_h = max(1, int(h * scale))
    return img.resize((new_w, new_h), Image.Resampling.LANCZOS)


def encode_result_image(result_image: np.ndarray) -> str:
    rgb = cv2.cvtColor(result_image, cv2.COLOR_BGR2RGB)
    buf = BytesIO()
    Image.fromarray(rgb).save(buf, format='JPEG', quality=RESULT_JPEG_QUALITY, optimize=True)
    return base64.b64encode(buf.getvalue()).decode('ascii')


def run_predict(image: Image.Image):
    results = model.predict(image, save=False, verbose=False, imgsz=INFER_IMGSZ)
    result = results[0]
    detections = []
    max_conf = 0.0
    if result.boxes is not None and len(result.boxes):
        for box in result.boxes:
            cls_id = int(box.cls[0])
            conf = float(box.conf[0])
            max_conf = max(max_conf, conf)
            detections.append({
                'classId': cls_id,
                'className': CLASS_NAMES.get(cls_id, 'Scratched'),
                'confidence': round(conf, 4),
                'box': [round(float(v), 2) for v in box.xyxy[0].tolist()]
            })
    count = len(detections)
    plotted = cv2.cvtColor(result.plot(), cv2.COLOR_RGB2BGR)
    return detections, count, max_conf, plotted


@app.on_event('startup')
def warmup_model():
    """启动时预热模型，避免首次请求冷启动过慢"""
    blank = Image.new('RGB', (INFER_IMGSZ, INFER_IMGSZ), color=(128, 128, 128))
    model.predict(blank, save=False, verbose=False, imgsz=INFER_IMGSZ)


@app.get('/health')
def health():
    return {
        'status': 'ok',
        'modelPath': str(MODEL_PATH),
        'modelName': MODEL_PATH.name,
        'projectRoot': str(ROOT),
        'maxInferSize': MAX_INFER_SIZE,
        'inferImgSz': INFER_IMGSZ,
    }


@app.post('/predict')
async def predict(file: UploadFile = File(...)):
    try:
        raw = await file.read()
        input_image = load_image(raw)
        _, count, _, result_image = run_predict(input_image)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    bytes_io = BytesIO()
    Image.fromarray(result_image).save(bytes_io, format='PNG')
    bytes_io.seek(0)
    return StreamingResponse(bytes_io, media_type='image/png', headers={'num_scratches': f'{count}'})


@app.post('/predict/json')
async def predict_json(
    file: UploadFile = File(...),
    include_image: bool = Query(True, description='是否返回标注图 base64，关闭可加速纯检测')
):
    try:
        raw = await file.read()
        input_image = load_image(raw)
        detections, count, max_conf, result_image = run_predict(input_image)
        payload = {
            'defect': count > 0,
            'count': count,
            'maxConfidence': round(max_conf, 4),
            'detections': detections,
            'model': f'YOLOv8-Seg ({MODEL_PATH.name})',
            'algorithmVersion': 'SCRATCH_SEG_V1',
            'modelPath': str(MODEL_PATH),
        }
        if include_image:
            payload['resultImage'] = encode_result_image(result_image)
        return payload
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
