
// Откройте frameworks/base/packages/SystemUI/src/com/android/systemui/doze/DozeSensors.java 
// и добавьте этот метод в конец класса (перед последней закрывающей скобкой }):
	// --- 2T2W PATCH START ---
    public void reregisterTapSensor() {
        for (TriggerSensor s : mTriggerSensors) {
            if (s.mPulseReason == DozeLog.REASON_SENSOR_TAP) {
                s.updateListening();
                break;
            }
        }
    }
    // --- 2T2W PATCH END ---