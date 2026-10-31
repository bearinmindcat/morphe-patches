package android.os;

/**
 * Stand-in for One UI's VibrationEffect.SemMagnitudeType (names Samsung Reminder uses, no Samsung code): the alert
 * screens read its constants, which off One UI are missing. Only passed to One UI's own vibration calls, which answer
 * "absent" there.
 */
public enum VibrationEffect$SemMagnitudeType {
    TYPE_TOUCH,
    TYPE_NOTIFICATION,
}
