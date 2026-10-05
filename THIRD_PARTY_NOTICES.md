# 第三方许可

## 内置词库

新增词书使用 [ECDICT](https://github.com/skywind3000/ECDICT) 固定版本的词条、释义和音标。原 MIT 许可全文保存在 [ECDICT-LICENSE.txt](database/vocabulary/ECDICT-LICENSE.txt)，版本、校验值及各书词量见 [catalog.json](database/vocabulary/catalog.json)。选词规则与使用方法见 [词汇学习指南](docs/VOCABULARY.md)。未引入具体出版社词书的封面、原版编排或例句。

## 已退出当前版本的素材

此前版本包含第三方游戏图片、角色素材和宣传视频。当前已将 `cle/public/art/` 从版本跟踪中移除并忽略；前端公开资源改为 `cle/static/`，Docker 构建也排除旧目录。历史设计中未使用的组件和样式不再进入当前源码快照。

此变更不重写旧 Git 历史。旧提交仍可能含上述素材，版权归原权利人，不受项目 MIT 许可授权。已有本地目录可能仍保留未跟踪副本，请勿将其加入自己的发布包。

## 软件依赖与服务组件

Vue、Vue Router、Vite、Spring Boot 及其他依赖保留各自许可证。Docker 服务独立适用其组件许可，本项目 MIT 许可不替代这些条款。

当前 MinIO 从固定上游源码构建，镜像保留其 LICENSE；见 [Minio.Dockerfile](scripts/components/Minio.Dockerfile) 和 [运行说明](scripts/components/minio-runtime.md)。重新分发镜像或修改组件时应检查其上游许可证与源码提供要求。

模型供应商的 API 条款、费用及模型授权由供应商决定。本仓库不附带模型权重或供应商额度。
