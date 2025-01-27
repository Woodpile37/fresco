/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

#ifndef _PNG_CODEC_H_
#define _PNG_CODEC_H_

#include <jni.h>

#include "decoded_image.h"

namespace facebook {
namespace imagepipeline {
namespace png {

/**
 * Encodes given image using libpng and writtes encoded bytes
 * into provided output stream.
 *
 * @param env
 * @param decoded_image
 * @param os output stream to write data to
 * @param quality value passed to png encoder
 */
void encodePngIntoOutputStream(
    JNIEnv* env,
    DecodedImage& decoded_image,
    jobject os);

} } }


#endif /* _PNG_CODEC_H_ */
