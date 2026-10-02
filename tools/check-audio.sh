#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
audio_test_dir="$(mktemp -d)"
trap 'rm -rf "$audio_test_dir"' EXIT
java -m jdk.compiler/com.sun.tools.javac.Main -d "$audio_test_dir" \
  "$project_dir/app/src/main/java/com/projection/car/Pcm48StereoTo16Mono.java" \
  "$project_dir/tools/AudioResamplerCheck.java" \
  "$project_dir/app/src/main/java/com/projection/car/CarLifeFrameReader.java" \
  "$project_dir/app/src/main/java/com/projection/car/AppLogger.java" \
  "$project_dir/tools/DiagnosticsCheck.java" \
  "$project_dir/app/src/main/java/com/projection/car/TouchPadTapTracker.java" \
  "$project_dir/tools/TouchPadTapCheck.java" \
  "$project_dir/app/src/main/java/com/projection/car/PcmVolume.java" \
  "$project_dir/tools/PcmVolumeCheck.java" \
  "$project_dir/app/src/main/java/com/projection/car/TtsPcmConverter.java" \
  "$project_dir/tools/TtsFormatsCheck.java" \
  "$project_dir/app/src/main/java/com/projection/car/CursorMotion.java" \
  "$project_dir/tools/CursorMotionCheck.java" \
  "$project_dir/app/src/main/java/com/projection/car/AccessoryInputStream.java" \
  "$project_dir/app/src/main/java/com/projection/car/VideoQueueBudget.java" \
  "$project_dir/tools/ConnectionStabilityCheck.java" \
  "$project_dir/tools/stubs/android/media/projection/MediaProjection.java" \
  "$project_dir/app/src/main/java/com/projection/car/ProjectionBridge.java" \
  "$project_dir/tools/ProjectionBridgeCheck.java" \
  "$project_dir/app/src/main/java/com/projection/car/BridgePcmMixer.java" \
  "$project_dir/tools/BridgeAudioCheck.java" \
  "$project_dir/app/src/main/java/com/projection/car/H264BridgeFrames.java" \
  "$project_dir/tools/VideoBridgeCheck.java" \
  "$project_dir/app/src/main/java/com/projection/car/HuLiveness.java" \
  "$project_dir/tools/HuLivenessCheck.java"
java -cp "$audio_test_dir" com.projection.car.AudioResamplerCheck
java -cp "$audio_test_dir" com.projection.car.DiagnosticsCheck
java -cp "$audio_test_dir" com.projection.car.TouchPadTapCheck
java -cp "$audio_test_dir" com.projection.car.PcmVolumeCheck
java -cp "$audio_test_dir" com.projection.car.TtsFormatsCheck
java -cp "$audio_test_dir" com.projection.car.CursorMotionCheck
java -cp "$audio_test_dir" com.projection.car.ConnectionStabilityCheck
java -cp "$audio_test_dir" com.projection.car.ProjectionBridgeCheck
java -cp "$audio_test_dir" com.projection.car.BridgeAudioCheck

java -cp "$audio_test_dir" com.projection.car.VideoBridgeCheck

java -cp "$audio_test_dir" com.projection.car.HuLivenessCheck
