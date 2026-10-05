# 设计参考与第三方说明

## 原创代码与界面

原创代码适用根目录 [LICENSE](LICENSE) 的 MIT 许可。

界面参考 [LabML Annotated Deep Learning](https://nn.labml.ai/) 及其[源码仓库](https://github.com/labmlai/annotated_deep_learning_paper_implementations) 的研究笔记组织方式：说明与示例并排、细分隔线、可定位的标题和代码符号。当前阅读组件、样式、示例文字与图标独立实现，没有打包 LabML 的源代码、文章、品牌标识或图片。本项目不代表 LabML，没有隶属或背书关系。

## 已退出当前版本的素材

此前版本包含第三方游戏图片、角色素材和宣传视频。当前已将 `cle/public/art/` 从版本跟踪中移除并忽略；前端公开资源改为 `cle/static/`，Docker 构建也排除旧目录。历史设计中未使用的组件和样式不再进入当前源码快照。

此变更不重写旧 Git 历史。旧提交仍可能含上述素材，版权归原权利人，不受项目 MIT 许可授权。已有本地目录可能仍保留未跟踪副本，请勿将其加入自己的发布包。

## 软件依赖与服务组件

Vue、Vue Router、Vite、Spring Boot 及其他依赖保留各自许可证。Docker 服务独立适用其组件许可，本项目 MIT 许可不替代这些条款。

当前 MinIO 从固定上游源码构建，镜像保留其 LICENSE；见 [Minio.Dockerfile](scripts/components/Minio.Dockerfile) 和 [运行说明](scripts/components/minio-runtime.md)。重新分发镜像或修改组件时应检查其上游许可证与源码提供要求。

模型供应商的 API 条款、费用及模型授权由供应商决定。本仓库不附带模型权重或供应商额度。
