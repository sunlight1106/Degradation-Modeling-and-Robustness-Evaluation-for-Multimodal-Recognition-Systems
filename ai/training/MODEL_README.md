# 使用训练好的分类模型

1. 安装 Python 3.12。
2. 安装 CPU 版依赖：`python -m pip install torch==2.10.0 --index-url https://download.pytorch.org/whl/cpu`。
3. 在解压目录运行：`python predict.py "你想分类的一段文字"`。

`weights.pt` 是本次训练的神经网络权重；`training.json` 记录标签、参数、划分数量及逐轮指标；`dataset.json` 是你上传的训练数据。下载文件包含私人样本，分享前请自行确认内容。

这是小型文本分类模型，只预测训练数据中的类别，不是聊天模型。分数是模型输出，不代表经过校准的可信度。数据按类别抽取约 20% 留作验证；验证准确率来自这一个小样本划分，不代表真实使用效果。
