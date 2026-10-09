# 第三方许可

## 内置词库

新增词书使用 [ECDICT](https://github.com/skywind3000/ECDICT) 固定版本的词条、释义和音标。原 MIT 许可全文保存在 [ECDICT-LICENSE.txt](database/vocabulary/ECDICT-LICENSE.txt)，版本、校验值及各书词量见 [catalog.json](database/vocabulary/catalog.json)。选词规则与使用方法见 [词汇学习指南](docs/VOCABULARY.md)。未引入具体出版社词书的封面、原版编排或例句。

## 软件依赖与服务组件

词条缺失音标的离线补充使用 eng_to_ipa 0.0.2 的 CMU 字典转换结果，另含补充标注。保留 [eng_to_ipa MIT 许可](database/vocabulary/ENG-TO-IPA-LICENSE.txt) 和 [CMU 字典许可](database/vocabulary/CMUDICT-LICENSE.txt)。转换仅在生成数据时进行，不是运行时依赖。补充列表见 [phonetic-supplement.json](database/vocabulary/phonetic-supplement.json)。

Vue、Vue Router、Vite、Spring Boot 及其他依赖保留各自许可证。Docker 服务独立适用其组件许可，本项目 MIT 许可不替代这些条款。

当前 MinIO 从固定上游源码构建，镜像保留其 LICENSE；见 [Minio.Dockerfile](scripts/components/Minio.Dockerfile) 和 [运行说明](scripts/components/minio-runtime.md)。重新分发镜像或修改组件时应检查其上游许可证与源码提供要求。

模型供应商的 API 条款、费用及模型授权由供应商决定。本仓库不附带模型权重或供应商额度。
