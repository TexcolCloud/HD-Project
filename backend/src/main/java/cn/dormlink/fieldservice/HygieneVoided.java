package cn.dormlink.fieldservice;

/** Voiding the source inspection cancels corrective work, without claiming it passed review. */
record HygieneVoided(String inspectionId, String reason) {}
