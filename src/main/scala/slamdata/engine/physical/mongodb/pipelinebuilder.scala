package slamdata.engine.physical.mongodb

import scalaz._
import Scalaz._

import slamdata.engine.{RenderTree, Terminal, NonTerminal, Error}
import slamdata.engine.fp._

case class ExprPointer(schema: PipelineSchema, ref: ExprOp.DocVar)

sealed trait UnifyError extends Error
object UnifyError {
  case class UnexpectedExpr(schema: SchemaChange) extends UnifyError {
    def message = "Unexpected expression: " + schema 
  }
  case object CouldNotPatchRoot extends UnifyError {
    def message = "Could not patch ROOT"
  }
}

/**
 * A `PipelineBuilder` consists of a list of pipeline operations in *reverse*
 * order and a patch to be applied to all subsequent additions to the pipeline.
 *
 * This abstraction represents a work-in-progress, where more operations are 
 * expected to be patched and added to the pipeline. At some point, the `build`
 * method will be invoked to convert the `PipelineBuilder` into a `Pipeline`.
 */
final case class PipelineBuilder private (buffer: List[PipelineOp], patch: MergePatch, schange: SchemaChange = SchemaChange.Init) { self =>
  import PipelineBuilder._
  import PipelineOp._
  import ExprOp.{DocVar, GroupOp}

  def build: Pipeline = Pipeline(MoveRootToField :: buffer.reverse)

  def schema: PipelineSchema = PipelineSchema(buffer.reverse)

  def unify(that: PipelineBuilder)(f: (DocVar, DocVar) => PipelineBuilder): Error \/ PipelineBuilder = {
    import \&/._
    import cogroup.{Instr, ConsumeLeft, ConsumeRight, ConsumeBoth}

    def optRight[A, B](o: Option[A \/ B])(f: Option[A] => Error): Error \/ B = {
      o.map(_.fold(a => -\/ (f(Some(a))), b => \/- (b))).getOrElse(-\/ (f(None)))
    }

    lazy val step: ((SchemaChange, SchemaChange), PipelineOp \&/ PipelineOp) => 
           Error \/ ((SchemaChange, SchemaChange), Instr[PipelineOp]) = {
      case ((lschema, rschema), these) =>
        these match {
          case This(left) => 
            val rchange = SchemaChange.Init.nestField("right")

            for {
              rproj <- optRight(rchange.toProject)(_ => UnifyError.UnexpectedExpr(rchange))

              rschema2 = rschema.rebase(rchange)

              rec <- step((lschema, rschema2), Both(left, rproj))
            } yield rec
          
          case That(right) => 
            val lchange = SchemaChange.Init.nestField("left")

            for {
              lproj <- optRight(lchange.toProject)(_ => UnifyError.UnexpectedExpr(lchange))

              lschema2 = rschema.rebase(lchange)

              rec <- step((lschema2, rschema), Both(lproj, right))
            } yield rec

          case Both(g1 : GeoNear, g2 : GeoNear) => ???

          case Both(g1 : GeoNear, right) => ???

          case Both(left, g2 : GeoNear) => ???

          case Both(left : ShapePreservingOp, _) => 
            \/- ((lschema, rschema) -> ConsumeLeft(left :: Nil))

          case Both(_, right : ShapePreservingOp) => 
            \/- ((lschema, rschema) -> ConsumeRight(right :: Nil))

          case Both(g1 @ Group(_, _), g2 @ Group(_, _)) => ???

          case Both(g1 @ Group(_, _), right) => ???

          case Both(left, g2 @ Group(_, _)) => ???

          case Both(g1 @ Project(_), g2 @ Project(_)) => ???

          case Both(g1 @ Project(_), right) => ???

          case Both(left, g2 @ Project(_)) => ???

          case Both(r1 @ Redact(_), r2 @ Redact(_)) => ???

          case Both(u1 @ Unwind(_), u2 @ Unwind(_)) => ???

          case Both(u1 @ Unwind(_), r2 @ Redact(_)) => ???

          case Both(r1 @ Redact(_), u2 @ Unwind(_)) => ???
        }
    }

    cogroup.statefulE(this.buffer.reverse, that.buffer.reverse)((this.schange, that.schange))(step).flatMap {
      case ((lschema, rschema), list) =>
        val left  = lschema.patchRoot(SchemaChange.Init)
        val right = rschema.patchRoot(SchemaChange.Init)

        (left |@| right) { (left0, right0) =>
          val left  = left0.fold(_ => DocVar.ROOT(), DocVar.ROOT(_))
          val right = right0.fold(_ => DocVar.ROOT(), DocVar.ROOT(_))

          f(left, right)
        }.map { builder =>
          \/- (PipelineBuilder(builder.buffer ::: self.buffer, MergePatch.Id, builder.schange))
        }.getOrElse(-\/ (UnifyError.CouldNotPatchRoot))
    }
  }

  def makeObject(name: String): Error \/ PipelineBuilder = {
    ???
  }

  def makeArray: Error \/ PipelineBuilder = {
    ???
  }

  def objectConcat(that: PipelineBuilder): Error \/ PipelineBuilder = {
    ???
  }

  def arrayConcat(that: PipelineBuilder): Error \/ PipelineBuilder = {
    ???
  }

  def projectField(name: String): Error \/ PipelineBuilder = {
    ???
  }

  def projectIndex(index: Int): Error \/ PipelineBuilder = {
    ???
  }

  def groupBy(that: PipelineBuilder): Error \/ PipelineBuilder = {
    ???
  }

  def grouped(expr: GroupOp): Error \/ PipelineBuilder = {
    ???
  }

  private def add0(op: PipelineOp): MergePatchError \/ PipelineBuilder = {
    for {
      t <- patch(op)

      (ops2, patch2) = t
    } yield copy(buffer = ops2.reverse ::: buffer, patch = patch2)
  }

  def + (op: PipelineOp): MergePatchError \/ PipelineBuilder = {
    for {
      t   <- MoveToExprField(op)
      r   <- addAll0(t._1)
      r   <- if (op.isInstanceOf[ShapePreservingOp]) \/- (r) else r.add0(MoveRootToField)
    } yield r
  }

  def ++ (ops: List[PipelineOp]): MergePatchError \/ PipelineBuilder = {
    type EitherE[X] = MergePatchError \/ X

    ops.foldLeftM[EitherE, PipelineBuilder](this) {
      case (pipe, op) => pipe + op
    }
  }

  private def addAll0(ops: List[PipelineOp]): MergePatchError \/ PipelineBuilder = {
    type EitherE[X] = MergePatchError \/ X

    ops.foldLeftM[EitherE, PipelineBuilder](this) {
      case (pipe, op) => pipe.add0(op)
    }
  }

  /**
   * Absorbs the last pipeline op for which the partial function is defined.
   *
   * The specified builder will be merged into the history and its information
   * content available to the partial function (together with the patch for 
   * that side).
   */
  def absorbLast(that: PipelineBuilder)(f: PartialFunction[PipelineOp, MergePatch => PipelineOp]): MergePatchError \/ PipelineBuilder = {
    val rbuffer = buffer.reverse

    val index = (buffer.length - 1) - buffer.indexWhere(f.isDefinedAt _)

    if (index < 0) -\/ (MergePatchError.UnknownShape)
    else {
      val prefix = rbuffer.take(index)
      val op     = rbuffer(index)
      val suffix = rbuffer.drop(index + 1)

      for {
        t <- PipelineMerge.mergeOps(Nil, prefix, MergePatch.Id, that.buffer.reverse, MergePatch.Id).leftMap(MergePatchError.Pipeline.apply)

        (merged, lp, rp) = t

        t <- lp(op)

        (ops, lp) = t

        ops <-  ops.indexWhere(f.isDefinedAt _) match {
                  case i if (i < 0) => -\/ (MergePatchError.UnknownShape)
                  case i => 
                    val pre  = ops.take(i)
                    val op0  = ops(i)
                    val post = ops.drop(i + 1)

                    val op = f(op0)(rp)

                    \/- (pre ::: op :: post)
                }

        t <- lp.applyAll(suffix)

        (suffix, lp) = t
      } yield new PipelineBuilder((prefix ::: ops ::: suffix).reverse, this.patch >> lp)
    }
  }

  def patch(patch2: MergePatch)(f: (MergePatch, MergePatch) => MergePatch): PipelineBuilder = copy(patch = f(patch, patch2))

  def patchSeq(patch2: MergePatch) = patch(patch2)(_ >> _)

  def patchPar(patch2: MergePatch) = patch(patch2)(_ && _)

  def fby_old(that: PipelineBuilder): MergePatchError \/ PipelineBuilder = {
    for {
      t <- patch.applyAll(that.buffer.reverse)

      (thatOps2, thisPatch2) = t
    } yield PipelineBuilder(thatOps2.reverse ::: this.buffer, thisPatch2 >> that.patch)
  }

  def merge0(that: PipelineBuilder): MergePatchError \/ (PipelineBuilder, MergePatch, MergePatch) = mergeCustom(that)(_ >> _)

  def mergeCustom(that: PipelineBuilder)(f: (MergePatch, MergePatch) => MergePatch): MergePatchError \/ (PipelineBuilder, MergePatch, MergePatch) = {
    for {
      t <- PipelineMerge.mergeOps(Nil, this.buffer.reverse, this.patch, that.buffer.reverse, that.patch).leftMap(MergePatchError.Pipeline.apply)

      (ops, lp, rp) = t
    } yield (PipelineBuilder(ops.reverse, f(lp, rp)), lp, rp)
  }

  def merge(that: PipelineBuilder): MergePatchError \/ PipelineBuilder = merge0(that).map(_._1)
}
object PipelineBuilder {
  import PipelineOp._
  import ExprOp.{DocVar}

  private val ExprName = BsonField.Name("__sd_expr")
  private val ExprVar  = ExprOp.DocVar.ROOT(ExprName)

  private val MoveToExprField = MergePatch.Rename(ExprOp.DocVar.ROOT(), ExprVar)

  private val MoveRootToField = Project(Reshape.Doc(Map(ExprName -> -\/ (DocVar.ROOT()))))

  def empty = PipelineBuilder(Nil, MergePatch.Id)

  def apply(p: PipelineOp): MergePatchError \/ PipelineBuilder = empty + p

  implicit def PipelineBuilderRenderTree(implicit RO: RenderTree[PipelineOp]) = new RenderTree[PipelineBuilder] {
    override def render(v: PipelineBuilder) = NonTerminal("PipelineBuilder", v.buffer.reverse.map(RO.render(_)))
  }
}
