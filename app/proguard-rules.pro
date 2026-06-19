# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }

# Keep our model classes
-keep class com.example.vigil.Detection { *; }
-keep class com.example.vigil.Yolov8Detector { *; }