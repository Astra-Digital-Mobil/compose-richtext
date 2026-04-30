@file:Suppress("RemoveEmptyParenthesesFromAnnotationEntry")

package com.halilibo.richtext.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halilibo.richtext.ui.ColumnArrangement.Adaptive
import com.halilibo.richtext.ui.ColumnArrangement.Uniform
import com.halilibo.richtext.ui.string.MarkdownAnimationState
import com.halilibo.richtext.ui.string.RichTextRenderOptions
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Defines the visual style for a [Table].
 *
 * @param headerTextStyle The [TextStyle] used for header rows.
 * @param cellPadding The spacing between the contents of each cell and the borders.
 * @param borderColor The [Color] of the table border.
 * @param borderStrokeWidth The width of the table border.
 * @param headerBorderColor Optional override of the header's [Color], defaulting to borderColor.
 * @param drawVerticalDividers Whether to draw vertical dividers.
 */
@Immutable
public data class TableStyle(
  val headerTextStyle: TextStyle? = null,
  val cellTextStyle: TextStyle? = null,
  val cellPadding: TextUnit? = null,
  val columnArrangement: ColumnArrangement? = null,
  val borderColor: Color? = null,
  val borderStrokeWidth: Float? = null,
  val borderCornerRadius: Dp? = null,
  val headerBorderColor: Color? = null,
  val dividerStyle: DividerStyle? = null,
  val headerBackgroundColor: Color? = null
) {
  public companion object {
    public val Default: TableStyle = TableStyle()
  }
}

public sealed interface ColumnArrangement {
  public object Uniform : ColumnArrangement
  public class Adaptive(public val maxWidth: Dp) : ColumnArrangement
}

public sealed interface DividerStyle {
  public object Full : DividerStyle
  public object Minimal : DividerStyle
  public data class Custom(
    val outerBorder: Boolean = false,
    val horizontalLines: Boolean = false,
    val verticalLines: Boolean = false
  ) : DividerStyle
}

private val DefaultTableHeaderTextStyle = TextStyle(fontWeight = FontWeight.Bold)
private val DefaultTableCellTextStyle = TextStyle.Default
private val DefaultCellPadding = 8.sp
private val DefaultBorderColor = Color.Unspecified
private val DefaultColumnArrangement = ColumnArrangement.Uniform
private const val DefaultBorderStrokeWidth = 1f
private val DefaultDividerStyle = DividerStyle.Full
private val DefaultBorderRadius = 0.dp
private val DefaultHeaderBackgroundColor = Color.Unspecified

internal fun TableStyle.resolveDefaults() = TableStyle(
  headerTextStyle = headerTextStyle ?: DefaultTableHeaderTextStyle,
  cellTextStyle = cellTextStyle ?: DefaultTableCellTextStyle,
  cellPadding = cellPadding ?: DefaultCellPadding,
  columnArrangement = columnArrangement ?: DefaultColumnArrangement,
  borderColor = borderColor ?: DefaultBorderColor,
  borderStrokeWidth = borderStrokeWidth ?: DefaultBorderStrokeWidth,
  borderCornerRadius = borderCornerRadius ?: DefaultBorderRadius,
  headerBorderColor = headerBorderColor,
  dividerStyle = dividerStyle ?: DefaultDividerStyle,
  headerBackgroundColor = headerBackgroundColor ?: DefaultHeaderBackgroundColor,
)

public interface RichTextTableRowScope {
  public fun row(children: RichTextTableCellScope.() -> Unit)
}

public interface RichTextTableCellScope {
  public fun cell(children: @Composable RichTextScope.() -> Unit)
}

@Immutable
private data class TableRow(val cells: List<@Composable RichTextScope.() -> Unit>)

private class TableBuilder : RichTextTableRowScope {
  val rows = mutableListOf<RowBuilder>()

  override fun row(children: RichTextTableCellScope.() -> Unit) {
    rows += RowBuilder().apply(children)
  }
}

private class RowBuilder : RichTextTableCellScope {
  var row = TableRow(emptyList())

  override fun cell(children: @Composable RichTextScope.() -> Unit) {
    row = TableRow(row.cells + children)
  }
}

/**
 * Draws a table with an optional header row, and an arbitrary number of body rows.
 *
 * The style of the table is defined by the [RichTextStyle.tableStyle]&nbsp;[TableStyle].
 */
@Composable
public fun RichTextScope.Table(
  modifier: Modifier = Modifier,
  markdownAnimationState: MarkdownAnimationState = remember { MarkdownAnimationState() },
  richTextRenderOptions: RichTextRenderOptions = RichTextRenderOptions(),
  headerRow: (RichTextTableCellScope.() -> Unit)? = null,
  bodyRows: RichTextTableRowScope.() -> Unit
) {
  val tableStyle = currentRichTextStyle.resolveDefaults().tableStyle!!
  val alpha = rememberMarkdownFade(richTextRenderOptions, markdownAnimationState)
  val contentColor = currentContentColor
  val header = remember(headerRow) {
    headerRow?.let { RowBuilder().apply(headerRow).row }
  }
  val rows = remember(bodyRows) {
    TableBuilder().apply(bodyRows).rows.map { it.row }
  }
  val columns = remember(header, rows) {
    max(
      header?.cells?.size ?: 0,
      rows.maxByOrNull { it.cells.size }?.cells?.size ?: 0
    )
  }
  if (columns == 0) {
    // Malformed tables can reach the renderer with no cells; skip them instead of crashing.
    return
  }
  val headerStyle = currentTextStyle.merge(tableStyle.headerTextStyle)
  val cellStyle = currentTextStyle.merge(tableStyle.cellTextStyle)
  val cellPadding = with(LocalDensity.current) {
    tableStyle.cellPadding!!.toDp()
  }
  val cellModifier = Modifier
    .clipToBounds()
    .padding(cellPadding)

  val styledRows = remember(header, rows, cellModifier) {
    buildList {
      header?.let { headerRow ->
        // Type inference seems to puke without explicit parameters.
        @Suppress("RemoveExplicitTypeArguments")
        add(headerRow.cells.map<@Composable RichTextScope.() -> Unit, @Composable () -> Unit> { cell ->
          @Composable {
            textStyleBackProvider(headerStyle) {
              BasicRichText(
                modifier = cellModifier,
                children = cell
              )
            }
          }
        })
      }

      rows.mapTo(this) { row ->
        @Suppress("RemoveExplicitTypeArguments")
        row.cells.map<@Composable RichTextScope.() -> Unit, @Composable () -> Unit> { cell ->
          @Composable {
            textStyleBackProvider(cellStyle) {
              BasicRichText(
                modifier = cellModifier,
                children = cell
              )
            }
          }
        }
      }
    }
  }

  // For some reason borders don't get drawn in the Preview, but they work on-device.
  val columnArrangement = tableStyle.columnArrangement!!
  val cellSpacing = tableStyle.borderStrokeWidth!!
  val density = LocalDensity.current
  val measurer = remember(columnArrangement, cellSpacing, density) {
    when (columnArrangement) {
      is Uniform -> UniformTableMeasurer(cellSpacing)
      is Adaptive -> {
        val maxWidth = with(density) { columnArrangement.maxWidth.toPx() }.roundToInt()
        AdaptiveTableMeasurer(maxWidth)
      }
    }
  }

  val baseModifier = modifier.graphicsLayer { this.alpha = alpha.value }
  val tableModifier = if (columnArrangement is Adaptive) {
    baseModifier.horizontalScroll(rememberScrollState())
  } else {
    baseModifier
  }

  val borderColor = tableStyle.borderColor!!.takeOrElse { contentColor }
  TableLayout(
    columns = columns,
    rows = styledRows,
    hasHeader = header != null,
    cellSpacing = tableStyle.borderStrokeWidth,
    tableMeasurer = measurer,
    drawDecorations = { layoutResult ->
      Modifier.drawTableBorders(
        rowOffsets = layoutResult.rowOffsets,
        columnOffsets = layoutResult.columnOffsets,
        borderColor = borderColor,
        borderRadius = tableStyle.borderCornerRadius!!,
        borderStrokeWidth = tableStyle.borderStrokeWidth,
        headerBorderColor = tableStyle.headerBorderColor ?: borderColor,
        dividerStyle = tableStyle.dividerStyle!!,
      )
    },
    drawHeaderBackgroundDecorations = { height: Float ->
      Modifier.drawTableHeaderBackground(
        height = height,
        hasHeader = header != null,
        headerBackgroundColor = tableStyle.headerBackgroundColor!!,
        borderRadius = tableStyle.borderCornerRadius!!,
      )
    },
    modifier = tableModifier
  )
}

private fun Modifier.drawTableBorders(
  rowOffsets: List<Float>,
  columnOffsets: List<Float>,
  borderRadius: Dp,
  borderColor: Color,
  borderStrokeWidth: Float,
  headerBorderColor: Color,
  dividerStyle: DividerStyle,
) = drawBehind {
  val cornerRadiusPx = borderRadius.toPx()
  val cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)

  when (dividerStyle) {

    is DividerStyle.Full -> {
      drawInnerHorizontalLines(rowOffsets, headerBorderColor, borderColor, borderStrokeWidth)
      drawInnerVerticalLines(columnOffsets, borderColor, borderStrokeWidth)
      drawOuterBorders(borderColor, cornerRadius, borderStrokeWidth)
    }

    is DividerStyle.Custom -> {
      if (dividerStyle.horizontalLines) drawInnerHorizontalLines(
        rowOffsets,
        headerBorderColor,
        borderColor,
        borderStrokeWidth
      )
      if (dividerStyle.verticalLines) drawInnerVerticalLines(
        columnOffsets,
        borderColor,
        borderStrokeWidth
      )
      if (dividerStyle.outerBorder) drawOuterBorders(borderColor, cornerRadius, borderStrokeWidth)
    }

    is DividerStyle.Minimal -> {
      drawInnerHorizontalLines(rowOffsets, headerBorderColor, borderColor, borderStrokeWidth)
    }
  }
}

private fun DrawScope.drawOuterBorders(
  borderColor: Color,
  cornerRadius: CornerRadius,
  borderStrokeWidth: Float
) {
  drawRoundRect(
    color = borderColor,
    topLeft = Offset.Zero,
    size = size,
    cornerRadius = cornerRadius,
    style = Stroke(width = borderStrokeWidth),
  )
}

private fun DrawScope.drawInnerVerticalLines(
  columnOffsets: List<Float>,
  borderColor: Color,
  borderStrokeWidth: Float
) {
  columnOffsets.forEachIndexed { i, position ->
    if (i in 1 until columnOffsets.size - 1) {
      drawLine(
        color = borderColor,
        start = Offset(position, 0f),
        end = Offset(position, size.height),
        strokeWidth = borderStrokeWidth,
      )
    }
  }
}

private fun DrawScope.drawInnerHorizontalLines(
  rowOffsets: List<Float>,
  headerBorderColor: Color,
  borderColor: Color,
  borderStrokeWidth: Float
) {
  rowOffsets.forEachIndexed { i, position ->
    if (i in 1 until rowOffsets.size - 1) {
      drawLine(
        color = if (i == 1) headerBorderColor else borderColor,
        start = Offset(0f, position),
        end = Offset(size.width, position),
        strokeWidth = borderStrokeWidth,
      )
    }
  }
}

private fun Modifier.drawTableHeaderBackground(
  height: Float,
  hasHeader: Boolean,
  headerBackgroundColor: Color,
  borderRadius: Dp,
) = drawBehind {
  val cornerRadiusPx = borderRadius.toPx()
  val cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)

  if (hasHeader && headerBackgroundColor.isSpecified) {
    val headerPath = Path().apply {
      addRoundRect(
        RoundRect(
          0f, 0f, size.width, height,
          topLeftCornerRadius = cornerRadius,
          topRightCornerRadius = cornerRadius,
          bottomRightCornerRadius = CornerRadius.Zero,
          bottomLeftCornerRadius = CornerRadius.Zero,
        )
      )
    }
    drawPath(headerPath, headerBackgroundColor)
  }
}
