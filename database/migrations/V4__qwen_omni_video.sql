UPDATE model_definition
SET code = 'qwen3.5-omni-plus-video',
    name = 'Qwen3.5 Omni Plus · 音视频分析',
    description = '同时理解画面、语音和音效，用降噪音轨进行前后结果对照'
WHERE code = 'qwen3-vl-plus-video' AND version = '2026-08-22';
