package workflow.graph

// rough idea given incrementalism: do everything it can in the base executor (which may be shared w/ other things) w/o inserting sources.
// then create a "final executor" that is the base one w/ sources inserted, and optimized using the EquivalentNodeMerge optimizer.
// The final value execution happens on that "final executor"
// This two stage process allows "intuitive things" to happen a source being passed in is already processed elsewhere in the pipeline (e.g. making sure to reuse a cacher),
// while pipeline fitting results can be reused across multiple pipeline applies, as they all share the same base executor.
abstract class GraphExecution[T](
    executor: GraphExecutor,
    sources: Map[SourceId, Operator],
    sink: SinkId,
    expressionToOutput: Expression => T
  ) {
  private var _executor: GraphExecutor = executor
  private var _sources: Map[SourceId, Operator] = sources
  private var _sink: SinkId = sink

  protected def getExecutor: GraphExecutor = _executor

  private[graph] def setExecutor(executor: GraphExecutor): Unit = {
    this._executor = executor
  }

  private[graph] def setSources(sources: Map[SourceId, Operator]): Unit = {
    this._sources = sources
  }

  private[graph] def setSink(sink: SinkId): Unit = {
    this._sink = sink
  }

  private[graph] def getSources: Map[SourceId, Operator] = _sources
  private[graph] def getSink: SinkId = _sink

  private var ranExecution: Boolean = false
  private lazy val finalExecutor: GraphExecutor = {
    if (getSources.nonEmpty) {
      getExecutor.partialExecute(getSink)

      val unmergedGraph = _sources.foldLeft(getExecutor.getGraph) {
        case (curGraph, (sourceId, sourceOp)) => {
          val (graphWithDataset, nodeId) = getExecutor.getGraph.addNode(sourceOp, Seq())
          graphWithDataset.replaceDependency(sourceId, nodeId).removeSource(sourceId)
        }
      }

      // Note: The existing executor state should not have any value stored at the removed source,
      // hence we can just reuse it
      val (newGraph, newState) = EquivalentNodeMergeOptimizer.execute(unmergedGraph, getExecutor.getState)

      ranExecution = true

      new GraphExecutor(newGraph, newState, optimize = false)
    } else {
      getExecutor
    }
  }

  private[graph] def getGraph: Graph = if (ranExecution) {
    finalExecutor.getGraph
  } else {
    _sources.foldLeft(getExecutor.getGraph) {
      case (curGraph, (sourceId, sourceOp)) => {
        val (graphWithDataset, nodeId) = getExecutor.getGraph.addNode(sourceOp, Seq())
        graphWithDataset.replaceDependency(sourceId, nodeId).removeSource(sourceId)
      }
    }
  }

  private[graph] def getState: Map[GraphId, Expression] = if (ranExecution) {
    finalExecutor.getState
  } else {
    getExecutor.getState
  }

  final def get(): T = expressionToOutput(finalExecutor.execute(getSink))
}
