

#include "be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment.h"
#include <ale_interface.hpp>
#include <iostream>

using namespace std;

ALEInterface* ALE;


JNIEXPORT void JNICALL Java_be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment_loadROM
  (JNIEnv * env, jobject o, jstring rom){
	 if(ALE == NULL){
		 ALE = new ALEInterface();
	 }

	 const char *romString = env->GetStringUTFChars(rom, 0);
	 ALE->loadROM(romString);
	 env->ReleaseStringUTFChars(rom, romString);

	 return;
}


JNIEXPORT jint JNICALL Java_be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment_getActions
  (JNIEnv * env, jobject o){
	 if(ALE == NULL){
		 ALE = new ALEInterface();
	 }
	 ActionVect minimal_actions = ALE->getMinimalActionSet();
	 return minimal_actions.size();
}


JNIEXPORT jint JNICALL Java_be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment_performAction
  (JNIEnv * env, jobject o, jint action){
	return ALE->act(ALE->getMinimalActionSet()[action]);
}


JNIEXPORT void JNICALL Java_be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment_resetGame
  (JNIEnv * env, jobject o){
	ALE->reset_game();
}


JNIEXPORT jfloatArray JNICALL Java_be_iminds_iot_dianne_rl_ale_ArcadeLearningEnvironment_getScreen
  (JNIEnv * env, jobject o){
	if(ALE->game_over()){
		return NULL;
	}

	ALEScreen screen = ALE->getScreen();

	pixel_t* screen_data = screen.getArray();
	pixel_t* s = screen_data;

	int pixels = 33600;
	int size = 3*pixels;
	jfloatArray result;
	result = env->NewFloatArray(size);

	int k, i;
	jfloat data[size];
	jfloat* ptr_r = data;
	jfloat* ptr_g = &data[pixels];
	jfloat* ptr_b = &data[2*pixels];

	int r,g,b;
	for (i = 0; i < pixels; i++) {
		pixel_t pixel = *s++;

		ALE->theOSystem->colourPalette().getRGB(pixel, r, g, b);

		*ptr_r++ = r / 255.0f;
		*ptr_g++ = g / 255.0f;
		*ptr_b++ = b / 255.0f;
	}


	env->SetFloatArrayRegion(result, 0, size, data);

	return result;
}
