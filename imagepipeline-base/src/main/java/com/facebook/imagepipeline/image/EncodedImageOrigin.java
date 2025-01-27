/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.image;

import com.facebook.infer.annotation.Nullsafe;

@Nullsafe(Nullsafe.Mode.STRICT)
public enum EncodedImageOrigin {
  NOT_SET("not_set"),
  NETWORK("network"),
  DISK("disk"),
  ENCODED_MEM_CACHE("encoded_mem_cache");

  private final String mOrigin;

  EncodedImageOrigin(String origin) {
    mOrigin = origin;
  }

  @Override
  public String toString() {
    return mOrigin;
  }
}
