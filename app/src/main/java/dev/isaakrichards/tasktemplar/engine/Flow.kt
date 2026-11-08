package dev.isaakrichards.tasktemplar.engine

import dev.isaakrichards.tasktemplar.model.Step
import kotlinx.coroutines.yield

/**
 * Runs a flat action list that contains If / Else If / Else / End If and
 * Loop / Break / Continue / End Loop markers (the same flat layout MacroDroid uses).
 * Pure Kotlin so the control flow can be unit-tested off-device.
 */
class Flow(private val acts: List<Step>) {

    private val n = acts.size
    private val ifOwner = HashMap<Int, Int>()          // else_if / else / end_if index -> its if index
    private val ifMarkers = HashMap<Int, MutableList<Int>>() // if index -> its else_if / else indexes
    private val ifEnd = HashMap<Int, Int>()
    private val loopEnd = HashMap<Int, Int>()
    private val loopStart = HashMap<Int, Int>()

    init {
        val ifs = ArrayDeque<Int>()
        val loops = ArrayDeque<Int>()
        acts.forEachIndexed { i, s ->
            when (s.type) {
                "if" -> {
                    ifs.addLast(i)
                    ifMarkers[i] = mutableListOf()
                }
                "else_if", "else" -> ifs.lastOrNull()?.let { owner ->
                    ifMarkers.getValue(owner).add(i)
                    ifOwner[i] = owner
                }
                "end_if" -> ifs.removeLastOrNull()?.let { owner ->
                    ifEnd[owner] = i
                    ifOwner[i] = owner
                }
                "loop" -> loops.addLast(i)
                "end_loop" -> loops.removeLastOrNull()?.let { start ->
                    loopEnd[start] = i
                    loopStart[i] = start
                }
            }
        }
    }

    enum class Result { DONE, STOPPED, STEP_LIMIT }

    /**
     * @param cond evaluates an If / Else If / Loop-while step's conditions
     * @param loopCount how many times a "count" loop should run
     * @param onPass called with the 1-based pass number each time a loop begins a pass
     * @param exec runs a normal (non-flow) action
     */
    suspend fun run(
        cond: (Step) -> Boolean,
        loopCount: (Step) -> Int,
        onPass: (Int) -> Unit,
        maxSteps: Int = 1_000_000,
        exec: suspend (Step) -> Unit,
    ): Result {
        val passes = HashMap<Int, Int>()
        val active = ArrayDeque<Int>()
        var pc = 0
        var steps = 0

        fun endOfIf(start: Int) = ifEnd[start] ?: n
        fun endOfLoop(start: Int) = loopEnd[start] ?: n

        fun falseJump(start: Int): Int {
            for (m in ifMarkers[start].orEmpty()) {
                val s = acts[m]
                if (s.type == "else" || cond(s)) return m + 1
            }
            return endOfIf(start) + 1
        }

        while (pc < n) {
            if (++steps > maxSteps) return Result.STEP_LIMIT
            if (steps % 100 == 0) yield()
            val step = acts[pc]
            when (step.type) {
                "if" -> pc = if (cond(step)) pc + 1 else falseJump(pc)
                // Reached by falling out of a branch that ran: skip the rest of the chain.
                "else_if", "else" -> pc = ifOwner[pc]?.let { endOfIf(it) + 1 } ?: (pc + 1)
                "end_if" -> pc++
                "loop" -> {
                    if (active.lastOrNull() != pc) {
                        active.addLast(pc)
                        passes[pc] = 0
                    }
                    val done = passes[pc] ?: 0
                    val again = when (step.p("mode", "count")) {
                        "while" -> cond(step)
                        "forever" -> true
                        else -> done < loopCount(step)
                    }
                    if (again) {
                        passes[pc] = done + 1
                        onPass(done + 1)
                        pc++
                    } else {
                        active.removeLastOrNull()
                        passes.remove(pc)
                        pc = endOfLoop(pc) + 1
                    }
                }
                "end_loop" -> pc = loopStart[pc] ?: (pc + 1)
                "break" -> {
                    val start = active.removeLastOrNull()
                    pc = if (start == null) pc + 1 else {
                        passes.remove(start)
                        endOfLoop(start) + 1
                    }
                }
                "continue" -> pc = active.lastOrNull() ?: (pc + 1)
                "stop" -> return Result.STOPPED
                else -> {
                    exec(step)
                    pc++
                }
            }
        }
        return Result.DONE
    }
}
