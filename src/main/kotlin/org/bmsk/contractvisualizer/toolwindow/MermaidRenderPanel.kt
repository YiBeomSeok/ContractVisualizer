package org.bmsk.contractvisualizer.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import org.bmsk.contractvisualizer.model.ContractInfo
import org.bmsk.contractvisualizer.model.PropertyInfo
import org.bmsk.contractvisualizer.model.StateDefinition
import org.bmsk.contractvisualizer.model.VariantInfo
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.CubicCurve2D
import java.awt.geom.Path2D
import javax.swing.JPanel

enum class LayoutMode { HORIZONTAL, VERTICAL, FLOW }

class MermaidRenderPanel : JPanel(BorderLayout()) {

    private val canvas = DiagramCanvas()
    private val scrollPane = JBScrollPane(canvas)

    init { add(scrollPane, BorderLayout.CENTER) }

    fun updateContent(contract: ContractInfo) {
        canvas.contract = contract
        canvas.resetZoom()
        canvas.revalidate()
        canvas.repaint()
    }

    fun showEmptyState() {
        canvas.contract = null
        canvas.revalidate()
        canvas.repaint()
    }

    fun resetZoom() = canvas.resetZoom()

    fun toggleLayout() {
        canvas.layoutMode = when (canvas.layoutMode) {
            LayoutMode.HORIZONTAL -> LayoutMode.VERTICAL
            LayoutMode.VERTICAL -> LayoutMode.FLOW
            LayoutMode.FLOW -> LayoutMode.HORIZONTAL
        }
        canvas.resetZoom()
        canvas.revalidate()
        canvas.repaint()
    }

    val currentLayout: LayoutMode get() = canvas.layoutMode

    fun fitToView() {
        canvas.fitToView(scrollPane.viewport.width, scrollPane.viewport.height)
        canvas.revalidate()
        canvas.repaint()
    }
}

private class DiagramCanvas : JPanel() {

    var contract: ContractInfo? = null
    var layoutMode = LayoutMode.HORIZONTAL

    private var scale = 1.0
    private var panOffset = Point(0, 0)
    private var dragStart: Point? = null
    private var dragMoved = false
    private var zoomAccumulator = 0.0

    private val fontFamily = "Inter, .SF NS, Segoe UI, SansSerif"
    private val headerFont = Font(fontFamily, Font.BOLD, 13)
    private val labelFont = Font(fontFamily, Font.PLAIN, 12)
    private val smallFont = Font(fontFamily, Font.ITALIC, 11)
    private val paramFont = Font(fontFamily, Font.PLAIN, 10)
    private val badgeFont = Font(fontFamily, Font.BOLD, 9)
    private val arrowFont = Font(fontFamily, Font.PLAIN, 9)

    // Palette: low-saturation pastels + clear accents
    private val stateColor = JBColor(Color(0xEEF2FF), Color(0x1E1B4B))
    private val stateBorder = JBColor(Color(0x818CF8), Color(0x6366F1))
    private val eventColor = JBColor(Color(0xFFF7ED), Color(0x431407))
    private val eventBorder = JBColor(Color(0xFB923C), Color(0xF97316))
    private val effectColor = JBColor(Color(0xF0FDF4), Color(0x052E16))
    private val effectBorder = JBColor(Color(0x4ADE80), Color(0x22C55E))
    private val stateLineColor = JBColor(Color(0xA5B4FC), Color(0x4338CA))
    private val effectLineColor = JBColor(Color(0x86EFAC), Color(0x166534))
    private val stateHighlight = JBColor(Color(0x4338CA), Color(0xA5B4FC))
    private val effectHighlight = JBColor(Color(0xC2410C), Color(0xFDBA74))
    private val textColor = JBColor(Color(0x1E293B), Color(0xE2E8F0))
    private val subtitleColor = JBColor(Color(0x94A3B8), Color(0x64748B))
    private val canvasBg = JBColor(Color(0xF8FAFC), Color(0x1E1E2E))

    private val boxPadding = 16
    private val itemHeight = 28
    private val childItemHeight = 18
    private val separatorHeight = 10
    private val headerHeight = 32
    private val groupGap = 60
    private val boxMinWidth = 240
    private val indentWidth = 24
    private val cornerRadius = 12

    // --- Line model with expandable children ---

    private data class LineItem(
        val text: String,
        val isSubtle: Boolean = false,
        val badge: String? = null,
        val isChild: Boolean = false,
        val isLastChild: Boolean = false,
        val isSeparator: Boolean = false,
        val expandKey: String? = null,
        val childCount: Int = 0,
        val logicalIndex: Int = -1,
    )

    // Tracks which items are expanded, keyed by expandKey
    private val expandedItems = mutableSetOf<String>()

    private data class ItemLayout(val x: Int, val y: Int, val width: Int, val height: Int, val index: Int)

    private var stateItemLayouts = listOf<ItemLayout>()
    private var eventItemLayouts = listOf<ItemLayout>()
    private var effectItemLayouts = listOf<ItemLayout>()

    // Flattened display lines (recalculated each paint)
    private var stateDisplayLines = listOf<LineItem>()
    private var eventDisplayLines = listOf<LineItem>()
    private var effectDisplayLines = listOf<LineItem>()

    private var hoveredEventIndex = -1
    private var highlightedStateIndices = setOf<Int>()
    private var highlightedEffectIndices = setOf<Int>()

    private data class EventEffectLink(val eventIndex: Int, val effectIndex: Int)
    private data class EventStateLink(val eventIndex: Int, val stateFieldIndex: Int)
    private var effectLinks = listOf<EventEffectLink>()
    private var stateLinks = listOf<EventStateLink>()
    private var stateFieldNameToIndex = mapOf<String, Int>()

    private var stateBoxX = 0; private var stateBoxY = 0; private var stateBoxW = 0
    private var eventBoxX = 0; private var eventBoxY = 0; private var eventBoxW = 0
    private var effectBoxX = 0; private var effectBoxY = 0; private var effectBoxW = 0

    // Custom box position offsets (from drag & drop)
    private var stateOffset = Point(0, 0)
    private var eventOffset = Point(0, 0)
    private var effectOffset = Point(0, 0)
    private var draggingBox: Int = -1  // 0=state, 1=event, 2=effect, -1=none(pan)

    init {
        background = canvasBg

        val mouseHandler = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragStart = e.point
                dragMoved = false
                draggingBox = detectBoxHeader(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (!dragMoved) {
                    handleClick(e)
                }
                dragStart = null
                dragMoved = false
                draggingBox = -1
                cursor = Cursor.getDefaultCursor()
            }

            override fun mouseDragged(e: MouseEvent) {
                val start = dragStart ?: return
                val dx = e.x - start.x
                val dy = e.y - start.y
                if (!dragMoved && (Math.abs(dx) > 3 || Math.abs(dy) > 3)) {
                    dragMoved = true
                    cursor = if (draggingBox >= 0) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        else Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                }
                if (dragMoved) {
                    val scaledDx = (dx / scale).toInt()
                    val scaledDy = (dy / scale).toInt()
                    when (draggingBox) {
                        0 -> stateOffset = Point(stateOffset.x + scaledDx, stateOffset.y + scaledDy)
                        1 -> eventOffset = Point(eventOffset.x + scaledDx, eventOffset.y + scaledDy)
                        2 -> effectOffset = Point(effectOffset.x + scaledDx, effectOffset.y + scaledDy)
                        else -> panOffset = Point(panOffset.x + dx, panOffset.y + dy)
                    }
                    dragStart = e.point
                    repaint()
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                val mx = ((e.x - panOffset.x) / scale).toInt()
                val my = ((e.y - panOffset.y) / scale).toInt()
                val newHovered = eventItemLayouts.indexOfFirst { l ->
                    mx in l.x..(l.x + l.width) && my in l.y..(l.y + l.height)
                }
                if (newHovered != hoveredEventIndex) {
                    hoveredEventIndex = newHovered
                    updateHighlights()
                    repaint()
                }
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                if (e.isMetaDown) {
                    val delta = -e.preciseWheelRotation
                    zoomAccumulator += delta
                    if (Math.abs(zoomAccumulator) >= 0.5) {
                        val oldScale = scale
                        scale = (scale * if (zoomAccumulator > 0) 1.15 else 0.85).coerceIn(0.3, 3.0)
                        zoomAccumulator = 0.0
                        panOffset = Point(
                            (e.x - (e.x - panOffset.x) * (scale / oldScale)).toInt(),
                            (e.y - (e.y - panOffset.y) * (scale / oldScale)).toInt(),
                        )
                        revalidate()
                        repaint()
                    }
                    e.consume()
                } else {
                    val amt = 20
                    if (e.isShiftDown) panOffset = Point(panOffset.x - (e.preciseWheelRotation * amt).toInt(), panOffset.y)
                    else panOffset = Point(panOffset.x, panOffset.y - (e.preciseWheelRotation * amt).toInt())
                    repaint()
                }
            }
        }
        addMouseListener(mouseHandler)
        addMouseMotionListener(mouseHandler)
        addMouseWheelListener(mouseHandler)
    }

    private fun handleClick(e: MouseEvent) {
        val mx = ((e.x - panOffset.x) / scale).toInt()
        val my = ((e.y - panOffset.y) / scale).toInt()

        // Check all display lines for expandable items
        val allLayouts = listOf(
            stateDisplayLines to stateItemLayouts,
            eventDisplayLines to eventItemLayouts,
            effectDisplayLines to effectItemLayouts,
        )
        for ((lines, layouts) in allLayouts) {
            for ((idx, layout) in layouts.withIndex()) {
                if (mx in layout.x..(layout.x + layout.width) && my in layout.y..(layout.y + layout.height)) {
                    val line = lines.getOrNull(idx) ?: continue
                    val key = line.expandKey ?: continue
                    if (expandedItems.contains(key)) expandedItems.remove(key) else expandedItems.add(key)
                    revalidate()
                    repaint()
                    return
                }
            }
        }
    }

    private fun updateHighlights() {
        if (contract == null || hoveredEventIndex < 0) {
            highlightedStateIndices = emptySet()
            highlightedEffectIndices = emptySet()
            return
        }
        // Map display index back to logical index
        val hoveredLine = eventDisplayLines.getOrNull(hoveredEventIndex)
        val logicalIdx = hoveredLine?.logicalIndex ?: -1
        if (logicalIdx < 0) {
            highlightedStateIndices = emptySet()
            highlightedEffectIndices = emptySet()
            return
        }
        highlightedEffectIndices = effectLinks.filter { it.eventIndex == logicalIdx }
            .mapNotNull { link -> effectDisplayLines.indexOfFirst { it.logicalIndex == link.effectIndex }.takeIf { it >= 0 } }
            .toSet()
        highlightedStateIndices = stateLinks.filter { it.eventIndex == logicalIdx }
            .mapNotNull { link -> stateDisplayLines.indexOfFirst { it.logicalIndex == link.stateFieldIndex }.takeIf { it >= 0 } }
            .toSet()
    }

    fun resetZoom() {
        scale = 1.0
        panOffset = Point(20, 20)
        hoveredEventIndex = -1
        highlightedStateIndices = emptySet()
        highlightedEffectIndices = emptySet()
        stateOffset = Point(0, 0)
        eventOffset = Point(0, 0)
        effectOffset = Point(0, 0)
    }

    private fun detectBoxHeader(e: MouseEvent): Int {
        val mx = ((e.x - panOffset.x) / scale).toInt()
        val my = ((e.y - panOffset.y) / scale).toInt()

        // Check if click is on a box header area
        if (mx in stateBoxX..(stateBoxX + stateBoxW) && my in stateBoxY..(stateBoxY + headerHeight)) return 0
        if (mx in eventBoxX..(eventBoxX + eventBoxW) && my in eventBoxY..(eventBoxY + headerHeight)) return 1
        if (mx in effectBoxX..(effectBoxX + effectBoxW) && my in effectBoxY..(effectBoxY + headerHeight)) return 2
        return -1
    }

    fun fitToView(viewWidth: Int, viewHeight: Int) {
        if (contract == null || viewWidth <= 0 || viewHeight <= 0) return
        stateOffset = Point(0, 0)
        eventOffset = Point(0, 0)
        effectOffset = Point(0, 0)
        scale = 1.0 // calculate preferred size at 1x
        val pref = preferredSize
        val diagramW = (pref.width).toDouble()
        val diagramH = (pref.height).toDouble()
        if (diagramW <= 0 || diagramH <= 0) return

        val padding = 20
        val scaleX = (viewWidth - padding * 2) / diagramW
        val scaleY = (viewHeight - padding * 2) / diagramH
        scale = minOf(scaleX, scaleY).coerceIn(0.3, 3.0)

        // Center
        val scaledW = diagramW * scale
        val scaledH = diagramH * scale
        panOffset = Point(
            ((viewWidth - scaledW) / 2).toInt(),
            ((viewHeight - scaledH) / 2).toInt(),
        )
        hoveredEventIndex = -1
        highlightedStateIndices = emptySet()
        highlightedEffectIndices = emptySet()
    }

    override fun getPreferredSize(): Dimension {
        val c = contract ?: return Dimension(400, 300)
        val fm = getFontMetrics(labelFont)
        stateDisplayLines = getStateDisplayLines(c)
        eventDisplayLines = getEventDisplayLines(c)
        effectDisplayLines = getEffectDisplayLines(c)
        val sw = calcBoxWidth(fm, stateDisplayLines)
        val ew = calcBoxWidth(fm, eventDisplayLines)
        val efw = calcBoxWidth(fm, effectDisplayLines)
        val sh = calcBoxHeight(stateDisplayLines); val eh = calcBoxHeight(eventDisplayLines); val efh = calcBoxHeight(effectDisplayLines)
        val boxes = listOf(Triple(sw, sh, "s"), Triple(ew, eh, "e"), Triple(efw, efh, "ef"))

        return when (layoutMode) {
            LayoutMode.HORIZONTAL -> {
                val tw = ((groupGap + sw + groupGap + ew + groupGap + efw + groupGap) * scale).toInt() + 40
                Dimension(tw, ((maxOf(sh, eh, efh) + 100) * scale).toInt() + 40)
            }
            LayoutMode.VERTICAL -> {
                val maxW = maxOf(sw, ew, efw)
                Dimension(((maxW + groupGap * 2) * scale).toInt() + 40, ((sh + groupGap + eh + groupGap + efh + 100) * scale).toInt() + 40)
            }
            LayoutMode.FLOW -> {
                val positions = computeFlowPositions(boxes)
                val maxX = positions.indices.maxOf { positions[it].first + boxes[it].first }
                val maxY = positions.indices.maxOf { positions[it].second + boxes[it].second }
                Dimension(((maxX + groupGap) * scale).toInt() + 40, ((maxY + 60) * scale).toInt() + 40)
            }
        }
    }

    private fun computeFlowPositions(boxes: List<Triple<Int, Int, String>>): List<Pair<Int, Int>> {
        val availableWidth = ((parent?.width ?: 800) / scale).toInt() - 40
        val positions = mutableListOf<Pair<Int, Int>>()
        var curX = 0; var curY = 20; var rowHeight = 0
        for ((w, h, _) in boxes) {
            if (curX > 0 && curX + groupGap + w > availableWidth) {
                curY += rowHeight + groupGap; curX = 0; rowHeight = 0
            }
            positions.add(Pair(curX, curY))
            curX += w + groupGap; rowHeight = maxOf(rowHeight, h)
        }
        return positions
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val c = contract
        if (c == null) { drawEmptyState(g2); return }

        val saved = g2.transform
        g2.translate(panOffset.x, panOffset.y)
        g2.scale(scale, scale)

        val fm = g2.getFontMetrics(labelFont)
        stateDisplayLines = getStateDisplayLines(c)
        eventDisplayLines = getEventDisplayLines(c)
        effectDisplayLines = getEffectDisplayLines(c)
        val sw = calcBoxWidth(fm, stateDisplayLines); val ew = calcBoxWidth(fm, eventDisplayLines); val efw = calcBoxWidth(fm, effectDisplayLines)
        val sh = calcBoxHeight(stateDisplayLines); val eh = calcBoxHeight(eventDisplayLines); val efh = calcBoxHeight(effectDisplayLines)

        val sx: Int; val sy: Int; val ex: Int; val ey: Int; val efx: Int; val efy: Int
        when (layoutMode) {
            LayoutMode.HORIZONTAL -> {
                sx = 0; sy = 20; ex = sx + sw + groupGap; ey = 20; efx = ex + ew + groupGap; efy = 20
            }
            LayoutMode.VERTICAL -> {
                val maxW = maxOf(sw, ew, efw)
                sx = (maxW - sw) / 2; sy = 20; ex = (maxW - ew) / 2; ey = sy + sh + groupGap; efx = (maxW - efw) / 2; efy = ey + eh + groupGap
            }
            LayoutMode.FLOW -> {
                val boxes = listOf(Triple(sw, sh, "s"), Triple(ew, eh, "e"), Triple(efw, efh, "ef"))
                val positions = computeFlowPositions(boxes)
                sx = positions[0].first; sy = positions[0].second; ex = positions[1].first; ey = positions[1].second; efx = positions[2].first; efy = positions[2].second
            }
        }

        // Apply custom drag offsets
        stateBoxX = sx + stateOffset.x; stateBoxY = sy + stateOffset.y; stateBoxW = sw
        eventBoxX = ex + eventOffset.x; eventBoxY = ey + eventOffset.y; eventBoxW = ew
        effectBoxX = efx + effectOffset.x; effectBoxY = efy + effectOffset.y; effectBoxW = efw

        stateItemLayouts = buildItemLayouts(stateBoxX, stateBoxY, sw, stateDisplayLines)
        eventItemLayouts = buildItemLayouts(eventBoxX, eventBoxY, ew, eventDisplayLines)
        effectItemLayouts = buildItemLayouts(effectBoxX, effectBoxY, efw, effectDisplayLines)
        buildStateFieldMapping(c)
        effectLinks = buildEffectLinks(c)
        stateLinks = buildStateLinks(c)

        drawDefaultConnections(g2)

        drawGroup(g2, stateBoxX, stateBoxY, sw, getStateName(c), stateDisplayLines, stateColor, stateBorder, -1, highlightedStateIndices, stateHighlight)
        drawGroup(g2, eventBoxX, eventBoxY, ew, getEventName(c), eventDisplayLines, eventColor, eventBorder, hoveredEventIndex, emptySet(), eventBorder)
        drawGroup(g2, effectBoxX, effectBoxY, efw, getEffectName(c), effectDisplayLines, effectColor, effectBorder, -1, highlightedEffectIndices, effectHighlight)

        if (hoveredEventIndex >= 0) drawHoveredConnections(g2)

        g2.transform = saved
    }

    // --- Group drawing ---

    private fun drawGroup(
        g2: Graphics2D, x: Int, y: Int, width: Int,
        title: String, lines: List<LineItem>, bgColor: Color, borderColor: Color,
        hoveredIndex: Int, highlightedIndices: Set<Int>, highlightColor: Color,
    ): Int {
        val totalHeight = calcGroupTotalHeight(lines)

        // Soft shadow (2-layer)
        g2.color = Color(0, 0, 0, 8)
        g2.fillRoundRect(x + 4, y + 4, width, totalHeight, cornerRadius, cornerRadius)
        g2.color = Color(0, 0, 0, 15)
        g2.fillRoundRect(x + 1, y + 1, width, totalHeight, cornerRadius, cornerRadius)

        // Background
        g2.color = bgColor
        g2.fillRoundRect(x, y, width, totalHeight, cornerRadius, cornerRadius)

        // Border
        g2.color = borderColor
        g2.stroke = BasicStroke(1.5f)
        g2.drawRoundRect(x, y, width, totalHeight, cornerRadius, cornerRadius)

        // Header: light tint instead of solid fill
        g2.color = Color(borderColor.red, borderColor.green, borderColor.blue, 20)
        g2.fillRoundRect(x, y, width, headerHeight, cornerRadius, cornerRadius)
        g2.fillRect(x, y + headerHeight - 6, width, 6)
        // Header bottom line
        g2.color = Color(borderColor.red, borderColor.green, borderColor.blue, 40)
        g2.drawLine(x + 1, y + headerHeight, x + width - 1, y + headerHeight)

        // Header text in border color (not white)
        g2.color = borderColor
        g2.font = headerFont
        val hfm = g2.getFontMetrics(headerFont)
        g2.drawString(title, x + (width - hfm.stringWidth(title)) / 2, y + headerHeight / 2 + hfm.ascent / 2 - 1)

        var itemY = y + headerHeight + boxPadding
        for ((idx, line) in lines.withIndex()) {
            val h = lineHeight(line)

            // Separator: just a thin line with spacing
            if (line.isSeparator) {
                g2.color = Color(borderColor.red, borderColor.green, borderColor.blue, 25)
                g2.drawLine(x + boxPadding, itemY + h / 2, x + width - boxPadding, itemY + h / 2)
                itemY += h
                continue
            }

            val isHovered = idx == hoveredIndex
            val isHigh = idx in highlightedIndices

            // Children get subtle tinted background
            if (line.isChild) {
                g2.color = Color(0, 0, 0, 12)
                g2.fillRect(x + 4, itemY - 1, width - 8, h + 1)
                // Left indent guide
                g2.color = Color(borderColor.red, borderColor.green, borderColor.blue, 30)
                g2.drawLine(x + boxPadding + 6, itemY, x + boxPadding + 6, itemY + h)
            }

            if (isHovered) {
                g2.color = Color(borderColor.red, borderColor.green, borderColor.blue, 40)
                g2.fillRoundRect(x + 4, itemY - 2, width - 8, h, 6, 6)
            }
            if (isHigh) {
                g2.color = Color(highlightColor.red, highlightColor.green, highlightColor.blue, 50)
                g2.fillRoundRect(x + 4, itemY - 2, width - 8, h, 6, 6)
                g2.color = highlightColor
                g2.fillRoundRect(x + 4, itemY, 3, h - 4, 2, 2)
            }

            val textX = x + boxPadding + (if (line.isChild) indentWidth else 0) + (if (isHigh) 4 else 0)

            if (line.isChild) {
                g2.color = if (isHigh) highlightColor else subtitleColor
                g2.font = paramFont
            } else {
                g2.color = if (isHigh) highlightColor else if (line.isSubtle) subtitleColor else textColor
                g2.font = if (line.isSubtle && !isHigh) smallFont else labelFont
            }

            // Draw expand toggle with accent color
            if (line.expandKey != null) {
                val isExpanded = expandedItems.contains(line.expandKey)
                val arrow = if (isExpanded) "\u25BC" else "\u25B6"
                // Accent arrow
                val savedColor = g2.color
                g2.color = Color(borderColor.red, borderColor.green, borderColor.blue, if (isExpanded) 200 else 120)
                g2.font = arrowFont
                g2.drawString(arrow, textX, itemY + g2.fontMetrics.ascent + 1)
                // Text
                g2.color = savedColor
                g2.font = labelFont
                g2.drawString(line.text, textX + 14, itemY + g2.fontMetrics.ascent)
            } else if (!line.isChild) {
                g2.drawString(line.text, textX, itemY + g2.fontMetrics.ascent)
            } else {
                g2.drawString(line.text, textX, itemY + g2.fontMetrics.ascent)
            }

            // Badge
            if (line.badge != null) {
                val bfm = g2.getFontMetrics(badgeFont)
                val bw = bfm.stringWidth(line.badge) + 8
                val bx = x + width - bw - boxPadding
                g2.color = if (isHigh) Color(highlightColor.red, highlightColor.green, highlightColor.blue, 40)
                    else Color(borderColor.red, borderColor.green, borderColor.blue, 40)
                g2.fillRoundRect(bx, itemY, bw, 16, 8, 8)
                g2.color = if (isHigh) highlightColor else subtitleColor
                g2.font = badgeFont
                g2.drawString(line.badge, bx + 4, itemY + bfm.ascent)
            }

            itemY += h
        }
        return totalHeight
    }

    // --- Connections ---

    private fun drawDefaultConnections(g2: Graphics2D) {
        g2.stroke = BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (link in stateLinks) {
            val fromDispIdx = eventDisplayLines.indexOfFirst { it.logicalIndex == link.eventIndex && !it.isChild }
            val toDispIdx = stateDisplayLines.indexOfFirst { it.logicalIndex == link.stateFieldIndex && !it.isChild }
            if (fromDispIdx < 0 || toDispIdx < 0) continue
            g2.color = stateLineColor
            drawSmartConnection(g2, eventBoxX, eventBoxY, eventBoxW, calcGroupTotalHeight(eventDisplayLines),
                stateBoxX, stateBoxY, stateBoxW, calcGroupTotalHeight(stateDisplayLines),
                eventDisplayLines, effectDisplayLines, fromDispIdx, toDispIdx, stateDisplayLines, false)
        }
        for (link in effectLinks) {
            val fromDispIdx = eventDisplayLines.indexOfFirst { it.logicalIndex == link.eventIndex && !it.isChild }
            val toDispIdx = effectDisplayLines.indexOfFirst { it.logicalIndex == link.effectIndex && !it.isChild }
            if (fromDispIdx < 0 || toDispIdx < 0) continue
            g2.color = effectLineColor
            drawSmartConnection(g2, eventBoxX, eventBoxY, eventBoxW, calcGroupTotalHeight(eventDisplayLines),
                effectBoxX, effectBoxY, effectBoxW, calcGroupTotalHeight(effectDisplayLines),
                eventDisplayLines, effectDisplayLines, fromDispIdx, toDispIdx, effectDisplayLines, false)
        }
    }

    private fun drawHoveredConnections(g2: Graphics2D) {
        val hoveredLine = eventDisplayLines.getOrNull(hoveredEventIndex) ?: return
        val logicalIdx = hoveredLine.logicalIndex
        if (logicalIdx < 0 || hoveredLine.isChild) return

        for (link in stateLinks.filter { it.eventIndex == logicalIdx }) {
            val toDispIdx = stateDisplayLines.indexOfFirst { it.logicalIndex == link.stateFieldIndex && !it.isChild }
            if (toDispIdx < 0) continue
            g2.color = stateHighlight; g2.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            drawSmartConnection(g2, eventBoxX, eventBoxY, eventBoxW, calcGroupTotalHeight(eventDisplayLines),
                stateBoxX, stateBoxY, stateBoxW, calcGroupTotalHeight(stateDisplayLines),
                eventDisplayLines, stateDisplayLines, hoveredEventIndex, toDispIdx, stateDisplayLines, true)
        }
        for (link in effectLinks.filter { it.eventIndex == logicalIdx }) {
            val toDispIdx = effectDisplayLines.indexOfFirst { it.logicalIndex == link.effectIndex && !it.isChild }
            if (toDispIdx < 0) continue
            g2.color = effectHighlight; g2.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            drawSmartConnection(g2, eventBoxX, eventBoxY, eventBoxW, calcGroupTotalHeight(eventDisplayLines),
                effectBoxX, effectBoxY, effectBoxW, calcGroupTotalHeight(effectDisplayLines),
                eventDisplayLines, effectDisplayLines, hoveredEventIndex, toDispIdx, effectDisplayLines, true)
        }
    }

    private fun drawSmartConnection(
        g2: Graphics2D,
        fromBoxX: Int, fromBoxY: Int, fromBoxW: Int, fromBoxH: Int,
        toBoxX: Int, toBoxY: Int, toBoxW: Int, toBoxH: Int,
        fromLines: List<LineItem>, toLines: List<LineItem>,
        fromDispIdx: Int, toDispIdx: Int, targetLines: List<LineItem>,
        withArrow: Boolean,
    ) {
        val fromItemY = calcItemCenterY(fromBoxY, fromLines, fromDispIdx)
        val toItemY = calcItemCenterY(toBoxY, targetLines, toDispIdx)

        val sameRow = Math.abs(fromBoxY - toBoxY) < fromBoxH / 2
        if (sameRow) {
            val fromRight = fromBoxX + fromBoxW < toBoxX
            val x1 = if (fromRight) fromBoxX + fromBoxW else fromBoxX
            val x2 = if (fromRight) toBoxX else toBoxX + toBoxW
            if (withArrow) drawBezierArrow(g2, x1, fromItemY, x2, toItemY)
            else drawBezier(g2, x1, fromItemY, x2, toItemY)
        } else {
            val fromBelow = fromBoxY + fromBoxH < toBoxY
            val y1 = if (fromBelow) fromBoxY + fromBoxH else fromBoxY
            val y2 = if (fromBelow) toBoxY else toBoxY + toBoxH
            val angle = if (fromBelow) Math.PI / 2 else -Math.PI / 2
            val cp = Math.abs(y2 - y1).toDouble() * 0.4
            val cx1 = fromBoxX + fromBoxW / 2; val cx2 = toBoxX + toBoxW / 2
            g2.draw(CubicCurve2D.Double(cx1.toDouble(), y1.toDouble(), cx1.toDouble(), y1 + if (fromBelow) cp else -cp, cx2.toDouble(), y2 + if (fromBelow) -cp else cp, cx2.toDouble(), y2.toDouble()))
            if (withArrow) drawArrowHead(g2, cx2, y2, angle)
        }
    }

    private fun calcItemCenterY(boxY: Int, lines: List<LineItem>, dispIdx: Int): Int {
        var y = boxY + headerHeight + boxPadding
        for (i in 0 until dispIdx) { y += lineHeight(lines[i]) }
        return y + lineHeight(lines[dispIdx]) / 2
    }

    // --- Bezier ---

    private fun drawBezier(g2: Graphics2D, x1: Int, y1: Int, x2: Int, y2: Int) {
        val cp = Math.abs(x2 - x1).toDouble() * 0.4
        val left = x2 < x1
        g2.draw(CubicCurve2D.Double(x1.toDouble(), y1.toDouble(), x1 + if (left) -cp else cp, y1.toDouble(), x2 + if (left) cp else -cp, y2.toDouble(), x2.toDouble(), y2.toDouble()))
    }

    private fun drawBezierArrow(g2: Graphics2D, x1: Int, y1: Int, x2: Int, y2: Int) {
        drawBezier(g2, x1, y1, x2, y2)
        drawArrowHead(g2, x2, y2, if (x2 < x1) Math.PI else 0.0)
    }

    private fun drawArrowHead(g2: Graphics2D, x: Int, y: Int, angle: Double) {
        val len = 7
        val path = Path2D.Double()
        path.moveTo(x.toDouble(), y.toDouble())
        path.lineTo(x - len * Math.cos(angle - Math.PI / 6), y - len * Math.sin(angle - Math.PI / 6))
        path.lineTo(x - len * Math.cos(angle + Math.PI / 6), y - len * Math.sin(angle + Math.PI / 6))
        path.closePath()
        g2.fill(path)
    }

    // --- Layout helpers ---

    private fun lineHeight(line: LineItem): Int = when {
        line.isSeparator -> separatorHeight
        line.isChild -> childItemHeight
        else -> itemHeight
    }

    private fun buildItemLayouts(x: Int, y: Int, w: Int, lines: List<LineItem>): List<ItemLayout> {
        val result = mutableListOf<ItemLayout>()
        var itemY = y + headerHeight + boxPadding
        for ((i, line) in lines.withIndex()) {
            val h = lineHeight(line)
            result.add(ItemLayout(x, itemY, w, h, i))
            itemY += h
        }
        return result
    }

    private fun calcGroupTotalHeight(lines: List<LineItem>): Int {
        val contentH = lines.sumOf { lineHeight(it) }
        return headerHeight + contentH + boxPadding * 2
    }

    private fun buildStateFieldMapping(c: ContractInfo) {
        stateFieldNameToIndex = (c.state as? StateDefinition.DataClassState)
            ?.properties?.withIndex()?.associate { (i, p) -> p.name to i }
            ?: emptyMap()
    }

    private fun buildEffectLinks(c: ContractInfo): List<EventEffectLink> {
        val map = c.effects.withIndex().associate { (i, v) -> v.name to i }
        return c.events.flatMapIndexed { ei, event ->
            if (event.emitsEffects.isNotEmpty()) {
                event.emitsEffects.mapNotNull { name -> map[name]?.let { EventEffectLink(ei, it) } }
            } else {
                c.effects.withIndex().filter { (_, ef) -> namesMatch(event.name, ef.name) }.map { (efi, _) -> EventEffectLink(ei, efi) }
            }
        }
    }

    private fun buildStateLinks(c: ContractInfo) =
        c.events.flatMapIndexed { ei, event ->
            event.mutatesFields.mapNotNull { field -> stateFieldNameToIndex[field]?.let { EventStateLink(ei, it) } }
        }

    private fun namesMatch(eventName: String, effectName: String): Boolean {
        val n = eventName.removePrefix("On")
        if (n == effectName || effectName.contains(n) || n.contains(effectName)) return true
        val ew = splitCamelCase(n).toSet(); val fw = splitCamelCase(effectName).toSet()
        return (ew.intersect(fw) - setOf("to", "on", "is", "get", "set")).size >= 2
    }

    private fun splitCamelCase(name: String) = Regex("[A-Z][a-z]*").findAll(name).map { it.value.lowercase() }.toList()

    private fun drawEmptyState(g2: Graphics2D) {
        g2.color = subtitleColor; g2.font = headerFont
        val msg = "Open a *Contract.kt file to see its diagram"
        val fm = g2.getFontMetrics(headerFont)
        g2.drawString(msg, (width - fm.stringWidth(msg)) / 2, height / 2)
    }

    // --- Data extraction with expand/collapse ---

    private fun getStateName(c: ContractInfo) = c.state?.name ?: "${c.featureName}State"
    private fun getEventName(c: ContractInfo) = "${c.featureName}Event"
    private fun getEffectName(c: ContractInfo) = "${c.featureName}Effect"

    private fun getStateDisplayLines(c: ContractInfo): List<LineItem> {
        val s = c.state ?: return emptyList()
        return when (s) {
            is StateDefinition.DataClassState ->
                s.properties.mapIndexed { i, p -> LineItem(fmtProp(p), logicalIndex = i) } +
                s.derivedProperties.map { LineItem("${it.name}: ${it.type}", true, "derived") } +
                s.companionConstants.map { LineItem("${it.name} = ${it.defaultValue ?: "?"}", true, "const") }
            is StateDefinition.SealedState ->
                buildDisplayLinesWithSeparators(s.variants, "state")
        }
    }

    private fun getEventDisplayLines(c: ContractInfo): List<LineItem> {
        return buildDisplayLinesWithSeparators(contract?.events ?: emptyList(), "event")
    }

    private fun getEffectDisplayLines(c: ContractInfo): List<LineItem> {
        return buildDisplayLinesWithSeparators(contract?.effects ?: emptyList(), "effect")
    }

    private fun buildDisplayLinesWithSeparators(variants: List<VariantInfo>, prefix: String): List<LineItem> {
        val result = mutableListOf<LineItem>()
        for ((i, v) in variants.withIndex()) {
            if (i > 0 && needsSeparatorBefore(variants, i, prefix)) {
                result.add(LineItem("", isSeparator = true))
            }
            result.addAll(variantToDisplayLines("${prefix}_$i", v, i))
        }
        return result
    }

    private fun needsSeparatorBefore(variants: List<VariantInfo>, index: Int, prefix: String): Boolean {
        // Add separator if previous item was expanded (has visible children)
        val prevKey = "${prefix}_${index - 1}"
        val prevVariant = variants[index - 1]
        return prevVariant.params.isNotEmpty() && expandedItems.contains(prevKey)
    }

    private fun variantToDisplayLines(key: String, v: VariantInfo, logicalIndex: Int): List<LineItem> {
        if (v.params.isEmpty()) {
            return listOf(LineItem(v.name, logicalIndex = logicalIndex))
        }

        val expanded = expandedItems.contains(key)
        val lines = mutableListOf<LineItem>()

        if (expanded) {
            lines.add(LineItem(v.name, expandKey = key, childCount = v.params.size, logicalIndex = logicalIndex))
            for ((pi, p) in v.params.withIndex()) {
                lines.add(LineItem("${p.name}: ${p.type}", isChild = true, isLastChild = pi == v.params.lastIndex))
            }
        } else {
            lines.add(LineItem(v.name, expandKey = key, childCount = v.params.size, logicalIndex = logicalIndex, badge = "${v.params.size}"))
        }

        return lines
    }

    private fun fmtProp(p: PropertyInfo): String {
        val d = if (p.defaultValue != null) " = ${p.defaultValue}" else ""
        return "${p.name}: ${p.type}$d"
    }

    private fun calcBoxWidth(fm: FontMetrics, lines: List<LineItem>): Int {
        val maxW = lines.filter { !it.isSeparator }.maxOfOrNull {
            val indent = if (it.isChild) indentWidth else 0
            val toggle = if (it.expandKey != null) 14 else 0
            val badge = if (it.badge != null) 40 else 0
            fm.stringWidth(it.text) + indent + toggle + badge
        } ?: 0
        return maxOf(boxMinWidth, maxW + boxPadding * 2 + 10)
    }

    private fun calcBoxHeight(lines: List<LineItem>): Int = calcGroupTotalHeight(lines)
}
