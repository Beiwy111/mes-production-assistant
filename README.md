# 晶程智造——电脑显示器制造 MES 系统

**新电脑独立运行生产协作助手**：在项目根目录运行 `Initialize-Assistant.cmd`。首次初始化的依赖、队友需要填写的两把模型 Key 和验收方法见[新电脑部署说明](backend/assistant/deploy/NEW-COMPUTER.md)。

## 一、项目简介

“晶程智造”是一套面向电脑显示器制造场景的 MES 制造执行系统，覆盖客户下单、订单审核、生产计划、智能排产、工单派发、生产报工、质量检验、仓储管理、成本分析和售后质量追溯等业务流程。

系统在智能质检模块中集成了基于 YOLOv8 的屏幕缺陷识别模型，实现缺陷图片上传、模型推理、缺陷区域标注和检测结果展示。

## 二、主要功能

- 客户下单与订单审核
- 生产计划与智能排产
- 工单生成与智能派工
- 操作员生产报工
- 质量检验与不合格品管理
- YOLOv8 屏幕缺陷识别
- 原材料及成品库存管理
- 设备运行与维护管理
- 生产异常与安灯报警
- 成本统计与利润分析
- 售后反馈与质量追溯
- 多角色用户及权限管理

## 三、技术栈

### 前端

- Vue 3
- Element Plus
- Vite
- Axios
- ECharts
- Pinia
- Vue Router

### 后端

- Java 17
- Spring Boot
- MyBatis
- MySQL 8.0
- Redis
- Maven

### 智能化功能

- Spring AI
- ChatClient
- ChatMemory
- Tool Calling
- Python
- PyTorch
- Ultralytics YOLOv8
- OpenCV
- FastAPI / Streamlit

## 四、项目目录

```text
computer
├─ backend                 Spring Boot 后端工程
├─ frontend                Vue 3 前端工程
├─ miniprogram             微信小程序工程
├─ Mobile-Phone-Defect     YOLOv8 缺陷检测代码及模型
├─ sql                     数据库脚本
├─ docs                    项目及部署文档
├─ .gitignore
└─ README.md
```

## 五、运行环境

建议使用以下环境：

| 软件 | 版本 |
|---|---|
| JDK | 17 |
| MySQL | 8.0 |
| Redis | 5.0.9 或以上 |
| Node.js | 20 或以上 |
| Python | 3.9 或 3.10 |
| Maven | 3.8 或以上 |

## 六、项目启动

系统建议按照以下顺序启动：

```text
1. 启动 MySQL
2. 导入 sql 目录中的数据库脚本
3. 启动 Redis
4. 启动 YOLOv8 模型服务
5. 启动 Spring Boot 后端
6. 启动 Vue 前端
7. 根据需要启动微信小程序
```

升级已有数据库时，在启动新版后端前，在业务库依次执行
`sql/migrations/quality_inspection_rounds.sql` 和 `sql/migrations/quality_sample_measurements.sql`。
这两份迁移从下一次实际操作开始留存检验版本、样机关联和实测记录。
完成这两份迁移后，从仓库根目录运行 `.\backend\assistant\rag\.venv\Scripts\python.exe .\backend\assistant\deploy\Initialize-MesAssistant.py`，按提示更新本机只读账号的表权限。

### 启动 YOLOv8 服务

```bash
cd Mobile-Phone-Defect
pip install -r requirements.txt
python api.py
```

实际启动文件以模型目录中的代码为准。

### 启动后端

```bash
cd backend
mvn spring-boot:run
```

也可以使用 IntelliJ IDEA 运行 Spring Boot 主启动类。

### 启动前端

```bash
cd frontend
npm install
npm run dev
```

详细的环境配置、数据库导入方式、模型部署步骤和系统演示流程，请查看：

```text
docs/晶程智造MES系统部署文档.docx
```

## 七、YOLOv8 智能质检

YOLOv8 模型代码位于：

```text
Mobile-Phone-Defect
```

模型权重文件一般位于：

```text
Mobile-Phone-Defect/models/production/best.pt
```

智能质检调用流程：

```text
上传屏幕图片
→ 调用 YOLOv8 模型
→ 执行缺陷识别
→ 返回缺陷位置和识别结果
→ 前端展示标注图片
→ 保存质检记录
```

## 八、模型来源说明

本系统的屏幕缺陷识别功能基于开源项目：

```text
Mobile-Phone-Defect-Segmentation-YOLOv8
```

该模型主要用于手机屏幕表面划痕检测和分割。

本项目在开源模型基础上完成了模型本地化部署、推理接口调用、MES 智能质检页面接入、检测结果展示以及质检业务关联。

当前模型主要用于屏幕表面划痕检测，后续可以通过补充电脑显示器缺陷数据，对坏点、漏光、亮度不均等缺陷类型进行扩展。

## 九、注意事项

- 请勿删除 YOLOv8 的 `best.pt` 模型权重文件。
- 首次运行前端需要执行 `npm install`。
- 首次运行模型服务需要安装 Python 依赖。
- 数据库用户名和密码需要根据部署电脑进行修改。
- 提交代码前请删除真实 API Key、数据库密码等敏感信息。
- `node_modules`、`target`、`.idea`、`.venv` 等目录不需要放入提交压缩包。

## 十、项目用途

本项目主要用于软件工程实践、课程设计及 MES 制造执行系统功能演示。

项目使用的部分模型和第三方组件来源于开源社区，相关权利归原作者所有。
