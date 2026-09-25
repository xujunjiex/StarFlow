# patches/ —— llama.cpp 下游补丁（1.25-bit / STQ1_0）

## 为什么有这些补丁

腾讯 Hy-MT2 的 `Hy-MT2-1.8B-1.25Bit.gguf`（461MB，官方从 ModelScope 分发）用的是**私有量化类型**，
文件里 224 个权重张量的类型号是 `42`。llama.cpp 官方仓库**没有**这个类型，需要一个下游补丁补上
`STQ1_0`（ternary 1.25-bit，含 ARM NEON vec_dot 内核）。

官方仓库 master 已经把 `42` 分配给了自己的 `Q2_0`，所以补丁里的 `STQ1_0` 必须占用 **`43`**：

| 引擎 | `42` | `43` | 能不能读官方那个 1.25-bit gguf |
|---|---|---|---|
| 老的预编译 `.so`（提交 `44b3f24` 加入，CLAUDE.md 记 `f8b355a9e`） | `STQ1_0` | — | ✅ 能（那时官方还没有 `Q2_0`） |
| 官方 master（今天） | `Q2_0` | — | ❌ 会把 1.25-bit 当 `Q2_0` 读 |
| 本仓库 submodule pin（PR #22836 head） | `Q2_0` | `STQ1_0` | ❌ 需要 App 侧「重打标」（见下） |

## 当前方案（C1）

- `llamacpp/src/main/cpp/llama.cpp` 是 **submodule**，pin 在 PR **#22836** 的 head
  （`ggml-cpu : add STQ1_0 ternary quantization with ARM NEON vec_dot kernel`）。
- 因为该 PR 的 `STQ1_0` 在 `43`，而官方分发的模型文件写的是 `42`，**App 在下载完成后会把文件里
  的 `42` 重打成 `43`**（见 `LlamaCppModelStore` / `GgufTypeRetag`）。这样：
  - 官方 `Q2_0` 保持干净的 `42`，用户导入的含 `Q2_0` 的标准 GGUF 不会被错解析；
  - 内置的 1.25-bit 模型继续可用（461MB 官方文件 + 224 处 4 字节改写，可校验、幂等）；
  - 引擎可以随时跟随官方升级。

## 什么时候用这里的 .patch 文件

`0001-ggml-stq1_0-1.25bit.patch` 是从 PR #22836 导出的完整 diff（**374 行 / 17 个文件**）。
它是**兜底**：万一那个 PR 被关闭、分支被删，或者我们要把引擎升级到官方更新的 master，
就用「官方提交 + 打这个补丁」的方式，不再依赖 PR 分支。

补丁是相对 PR 的 merge-base 生成的，**直接打在今天的 master 上会失败**（上游改了同区域的上下文）。
升级步骤（需要时执行）：

```bash
# 1) 在子模块里换到想用的官方提交
git -C llamacpp/src/main/cpp/llama.cpp fetch --depth 1 origin <official-sha>
git -C llamacpp/src/main/cpp/llama.cpp checkout <official-sha>

# 2) 尝试打补丁（先 --check）
git -C llamacpp/src/main/cpp/llama.cpp apply --check ../../../../patches/0001-ggml-stq1_0-1.25bit.patch
git -C llamacpp/src/main/cpp/llama.cpp apply         ../../../../patches/0001-ggml-stq1_0-1.25bit.patch

# 3) 若冲突：按冲突修好后，重新导出补丁覆盖本文件
git -C llamacpp/src/main/cpp/llama.cpp diff > ../../../../patches/0001-ggml-stq1_0-1.25bit.patch
```

## 新机器上拉源码注意（Windows 路径长度）

llama.cpp 的 `tools/ui` 有超长路径（>260 字符），未开长路径支持时 `git submodule update` 会在
checkout 阶段失败。用仓库脚本初始化：

```powershell
pwsh -File llamacpp/setup-submodule.ps1
```

脚本做的事：`core.longpaths=true` → 排除 `tools/ examples/ docs/ tests/`（我们用不到）→ 按 pin 的
SHA fetch + checkout。
