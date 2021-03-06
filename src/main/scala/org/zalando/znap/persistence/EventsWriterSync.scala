/**
  *
  * Copyright (C) 2016 Zalando SE
  *
  * This software may be modified and distributed under the terms
  * of the MIT license.  See the LICENSE file for details.
  */
package org.zalando.znap.persistence

import com.fasterxml.jackson.databind.JsonNode

trait EventsWriterSync {
  def init(): Unit
  def write(events: List[JsonNode]): Unit
}
