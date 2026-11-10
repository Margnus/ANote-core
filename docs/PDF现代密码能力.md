# 原PDF探针的AES-256密码能力

2026-11-10，PDF-05/C1第5项有限能力补验。现有原平台能力报告只有 RC4-128 合成文件，不能据此推断现代加密文件支持。本轮沿原 `PdfCapabilityProbe.java/.swift` 增加 `--password` 分支，不添加 PDF SDK、普通 App 功能、保存密码或生产资源协议。

## 固定样本与验收

原 `crop-rotations.pdf` 的四页内容流、CropBox 和固有旋转保持；pypdf 加密成 AES-256，字典 `/V=5 /R=6 /Length=256 /CFM=/AESV3`。公开测试口令为 `notecore-fixture`，不是用户凭证。原件 SHA256 `8c29b75c713114288652cbd9afe54a65fc82865543e8d5fd81d3bf8a737aa9ee`，加密件 `8301dc8f6450e09e2854a0917f1ae190a057a3996bd736f9719d660e56104565`，详见资产目录 `password-manifest.json`。

生成器使用运行时正常随机加密，不伪造固定随机源；第一次创建后保留固定字节，重复调用只验证已有密文与 manifest，拒绝重写不符的文件。主机已解密核对原内容/框/旋转，Poppler 72dpi 首页面渲染及视觉检查通过；它不替代平台结果。

每个平台分别验证初始锁定/无口令不能打开或渲染、错口令仍锁定、正确口令四页可渲染；每页对照同平台同一原明文的全部像素，再按原 recipe 独立核对四个色锚点及尺寸。不同平台不要求文字光栅逐像素一致。输出不是文字提取或文件结构检查替代渲染。

## 当前实际结果

Android API35/原持久 NoteCoreCurrent35 的实际 ART `app_process` 已通过，见[原始报告](../reports/pdf-password-art/report.json)。原 Java probe 实际重新编译，仅安装测试 APK；原预览 App 不重装，安装前后 APK 摘要相同，原5份笔记集合/字节保持。两个源 PDF 在设备端读回摘要保持，四页原明文/密文8张 PNG 全像素一致且32色锚点通过，四张加密件实际页面已查看。测试只在唯一 cache 目录写合成源/图片，没有普通UI输入或用户文件写入。

Apple 沿原 `apple-pdf.yml` 与 `collect_apple_pdf.py`，在原独立 simulator/同一 CoreGraphics probe 中增加该分支；原三文件 metadata/渲染检查保持，并分别保留 password 日志/八张 PNG 与源码指纹。返回后用原 `verify_apple_pdf_metadata.py` 核对固定 Git 源/产物、口令结果与独立色锚点。尚未取得新提交实际结果前保持待验，不继承原 RC4 或其它 workflow 通过。

采用 Android 此样本的系统能力，Apple 按自身结果采用或限制；本有限卡不扩成密码算法枚举。仍缺旧 OS/PDF 模块/厂商、复杂加密文件及实际用户文件矩阵。生产接入必须按真实 engine capability 显示支持/拒绝，保留原源，不记录明文口令；还需密码 UI、重试/取消、描述符/reader owner、资源事务、低空间/任务恢复、保存导出与真实设备。PDF-05/整体C1/M-1/113条原验收不据本卡晋升。

## 复验入口

使用捆绑 PDF Python（含 pypdf/cryptography/Pillow），先运行 `tools/generate_pdf_password_fixture.py` 只读验证固定件。按已有 JAVA_HOME/ANDROID_HOME/GRADLE_USER_HOME 执行 `-PenableAndroidTargets=true :android-app:assembleDebugAndroidTest`，再运行 `tools/verify_pdf_password_art.py --adb <adb绝对路径> --output <新的证据目录>`；目录必须未存在，拒绝覆盖历史结果。该工具只允许原5590/API35 AVD，不能用于任意用户设备。Apple 由既有隔离 CI 入口实际编译和执行。

## Apple实际结论与采用决定

固定8cd2976/CI42752132299在独立iOS Simulator上实际通过原CoreGraphics probe：无口令及错口令仍锁定、取页返回nil；正确口令四页可读取、与同平台明文全像素一致，独立色锚点/尺寸通过。原三份PDF的12页元数据/24图像检查也通过。12项固定Git输入、28产物及ZIP摘要核验，4张新AES页面已查看，见[像素结果](../reports/apple-pdf-password-ci-42752132299/verification.json)和[绑定回执](../reports/apple-pdf-password-ci-42752132299/artifact-binding.json)。这不是iOS密码UI、任意现代加密PDF、实际用户文件或物理设备证明。

采用该固定AES-256/R6样本在Android系统PdfRenderer与Apple CoreGraphics的密码/内容能力，本有限缺口收口；停止扩相似算法样本。后续按真实engine capability和资源/任务owner边界接原PDF纵切，验证密码UI/不保存明文/取消重试/低空间/原件保护/保存导出。PDF05未开发/待验收以及M-1/C1/生产前置保持。
