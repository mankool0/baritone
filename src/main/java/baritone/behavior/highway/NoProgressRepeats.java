/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior.highway;

/**
 * How many times in a row the same thing came round again with nothing gained in between. A
 * different key, or a progress count that has moved since the last time, starts it over, so only
 * a loop that is getting nowhere ever counts up.
 */
final class NoProgressRepeats<K> {
    private K key;
    private long progress;
    private int count;

    /**
     * Counts one more time round at {@code key}, with whatever measures progress at {@code progress}.
     *
     * @return how many times in a row that makes, this one included
     */
    int note(K key, long progress) {
        if (!key.equals(this.key) || progress != this.progress) {
            this.key = key;
            this.progress = progress;
            count = 0;
        }
        return ++count;
    }

    /** Forgets the key along with its count. */
    void clear() {
        key = null;
        count = 0;
    }
}
