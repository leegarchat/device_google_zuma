
// Откройте frameworks/base/packages/SystemUI/src/com/android/systemui/doze/DozeSensors.java 
// и добавьте этот метод в конец класса (перед последней закрывающей скобкой }):
	// --- DT2W PATCH START ---
    public void reregisterTapSensor() {
        for (TriggerSensor s : mTriggerSensors) {
            if (s.mPulseReason == DozeLog.REASON_SENSOR_TAP) {
                s.updateListening();
                break;
            }
        }
    }
    // --- DT2W PATCH END ---