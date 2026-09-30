# :sr —— native 模块。JNI 门面 SrNcnnNative 的方法名与 native 符号一一对应，
# 混淆会打断 external 绑定 → 必须 keep。
-keep class com.moe.starflow.sr.ncnn.SrNcnnNative { *; }
