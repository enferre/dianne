/* DO NOT EDIT THIS FILE - it is machine generated */
#include <jni.h>
/* Header for class be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment */

#ifndef _Included_be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment
#define _Included_be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment
#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment
 * Method:    loadROM
 * Signature: (Ljava/lang/String;)V
 */
JNIEXPORT void JNICALL Java_be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment_loadROM
  (JNIEnv *, jobject, jstring);

/*
 * Class:     be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment
 * Method:    getActions
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment_getActions
  (JNIEnv *, jobject);

/*
 * Class:     be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment
 * Method:    performAction
 * Signature: (I)I
 */
JNIEXPORT jint JNICALL Java_be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment_performAction
  (JNIEnv *, jobject, jint);

/*
 * Class:     be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment
 * Method:    resetGame
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment_resetGame
  (JNIEnv *, jobject);

/*
 * Class:     be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment
 * Method:    gameOver
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL Java_be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment_gameOver
  (JNIEnv *, jobject);

/*
 * Class:     be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment
 * Method:    getScreen
 * Signature: ()[F
 */
JNIEXPORT jfloatArray JNICALL Java_be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment_getScreen
  (JNIEnv *, jobject);

/*
 * Class:     be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment
 * Method:    setFrameskip
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment_setFrameskip
  (JNIEnv *, jobject, jint);

#ifdef __cplusplus
}
#endif
#endif