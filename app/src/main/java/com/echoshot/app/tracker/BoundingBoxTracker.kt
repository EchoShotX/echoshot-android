/* Copyright 2021 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================
*/

package com.echoshot.app.tracker

import androidx.annotation.VisibleForTesting
import com.echoshot.app.data.Person
import kotlin.math.max
import kotlin.math.min

/**
 * BoundingBoxTracker, which tracks objects based on bounding box similarity,
 * currently defined as intersection-over-union (IoU).
 */
class BoundingBoxTracker(config: TrackerConfig = TrackerConfig()) : AbstractTracker(config) {

    /**
     * Computes similarity based on intersection-over-union (IoU). See `AbstractTracker`
     * for more details.
     */
    override fun computeSimilarity(persons: List<Person>): List<List<Float>> {
        if (persons.isEmpty() && tracks.isEmpty()) {
            return emptyList()
        }
        return persons.map { person -> tracks.map { track -> iou(person, track.person) } }
    }

    /**
     * Computes the intersection-over-union (IoU) between a person and a track person.
     * @param person1 A person
     * @param person2 A track person
     * @return The IoU  between the person and the track person. This number is
     * between 0 and 1, and larger values indicate more box similarity.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun iou(person1: Person, person2: Person): Float {
        // 1) person1.boundingBox가 null이면 즉시 0 반환
        val box1 = person1.boundingBox ?: return 0f
        // 2) person2.boundingBox가 null이면 즉시 0 반환
        val box2 = person2.boundingBox ?: return 0f

        // 이제 box1, box2는 non-null RectF
        val xMin = max(box1.left,   box2.left)
        val yMin = max(box1.top,    box2.top)
        val xMax = min(box1.right,  box2.right)
        val yMax = min(box1.bottom, box2.bottom)
        if (xMin >= xMax || yMin >= yMax) return 0f

        val intersection = (xMax - xMin) * (yMax - yMin)
        val area1 = box1.width()  * box1.height()
        val area2 = box2.width()  * box2.height()
        return intersection / (area1 + area2 - intersection)
    }
}
