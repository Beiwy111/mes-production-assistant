@echo off
cd /d "%~dp0"
echo ========================================
echo  Mobile-Phone-Defect YOLO Service
echo  Model: models\production\best.pt
echo  URL  : http://localhost:8000
echo ========================================
where python >nul 2>&1 || (echo Python not found & pause & exit /b 1)
python -c "import ultralytics" >nul 2>&1 || (
  echo Installing dependencies...
  python -m pip install ultralytics opencv-python-headless fastapi uvicorn python-multipart Pillow
)
python -m uvicorn api:app --host 0.0.0.0 --port 8000 --workers 1
pause
