/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package hirain.carina;

@Backing(type="int")
enum StatusCode {
  OK = 0,
  TRY_AGAIN = 1,
  INVALID_ARG = 2,
  NOT_AVAILABLE = 3,
  ACCESS_DENIED = 4,
  INTERNAL_ERROR = 5,
  NOT_AVAILABLE_DISABLED = 6,
  NOT_AVAILABLE_SPEED_LOW = 7,
  NOT_AVAILABLE_SPEED_HIGH = 8,
  NOT_AVAILABLE_POOR_VISIBILITY = 9,
  NOT_AVAILABLE_SAFETY = 10,
}
