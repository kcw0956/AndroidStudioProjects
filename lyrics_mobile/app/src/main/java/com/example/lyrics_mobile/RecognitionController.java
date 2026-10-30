package com.example.lyrics_mobile;

/** Live recognition has a build-time gate as well as credential validation. */
public final class RecognitionController {
    public DataResult<SongInfo> recognize(AppSettings settings, OperationScope scope) {
        if (!BuildConfig.ACR_RECOGNITION_ENABLED) return DataResult.fail(new AiFailure("ACR_DISABLED", "주변 음악 인식은 준비 중이에요", "설정에서 자격정보를 미리 저장할 수 있어요. 현재는 녹음과 서버 호출을 하지 않아요.", AiFailure.Action.SETTINGS));
        String invalid = RecognitionConfig.validate(settings.getAcrHost(), settings.getAcrKey(), settings.getAcrSecret());
        if (!invalid.isEmpty()) return DataResult.fail(new AiFailure("ACR_CONFIG", "인식 설정을 확인해 주세요", invalid, AiFailure.Action.SETTINGS));
        try {
            byte[] sample = new AudioSampleRecorder().record(scope);
            scope.check();
            SongInfo song = new AcrCloudClient(scope).identify(settings.getAcrHost(), settings.getAcrKey(), settings.getAcrSecret(), sample);
            return song == null ? DataResult.fail(DataResult.missing("일치하는 곡이 없어요. 음악이 잘 들리는 곳에서 다시 시도해 주세요.")) : DataResult.ok(song);
        } catch (SecurityException e) { return DataResult.fail(new AiFailure("MIC_PERMISSION", "마이크 권한이 필요해요", "기기 앱 설정에서 마이크 권한을 허용해 주세요.", AiFailure.Action.SETTINGS)); }
        catch (Exception e) {
            return DataResult.fail(new AiFailure("ACR_FAILED", "음악 인식에 실패했어요", "네트워크, 마이크 또는 ACRCloud 자격정보·사용량을 확인해 주세요.", AiFailure.Action.RETRY));
        }
    }
}
