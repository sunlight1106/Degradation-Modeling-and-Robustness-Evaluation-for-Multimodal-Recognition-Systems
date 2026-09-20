UPDATE model_definition
SET code = 'deepseek-v4-flash-vision-exp-lpr',
    name = 'DeepSeek V4 Flash Vision · 车牌分析',
    version = '2026-08-21',
    description = '真实视觉 API：车牌文本、输入质量、退化因素与不确定性结构化分析'
WHERE code = 'lpr-robust-demo' AND version = '0.1.0';

UPDATE model_definition
SET code = 'deepseek-v4-flash-vision-exp-receipt',
    name = 'DeepSeek V4 Flash Vision · 票据分析',
    version = '2026-08-21',
    description = '真实视觉 API：OCR、关键字段抽取、图像质量和风险提示'
WHERE code = 'receipt-kie-demo' AND version = '0.1.0';
