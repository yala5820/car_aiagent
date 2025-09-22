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

import hirain.carina.PropConfigs;
import hirain.carina.ISoaCallback;
import hirain.carina.GetValueRequests;
import hirain.carina.SetValueRequests;
import hirain.carina.SubscribeOptions;

interface ISoaService {
  PropConfigs getAllPropConfigs();
  PropConfigs getPropConfigs(in int[] props);
  void getValues(in ISoaCallback callback, in GetValueRequests requests);
  void setValues(in ISoaCallback callback, in SetValueRequests requests);
  void subscribe(in ISoaCallback callback, in SubscribeOptions[] options);
  void unsubscribe(in ISoaCallback callback, in int[] propIds);
}
