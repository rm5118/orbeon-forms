/**
 * Copyright (C) 2011 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.control.controls

import org.w3c.dom.Node.ELEMENT_NODE
import org.orbeon.oxf.xforms.xbl.{Scope, XBLContainer}
import org.orbeon.oxf.xml.dom4j.Dom4jUtils
import org.xml.sax.helpers.AttributesImpl
import org.orbeon.oxf.xforms._
import analysis.PartAnalysisImpl
import collection.JavaConverters._
import control.{XFormsComponentControl, XFormsSingleNodeContainerControl, XFormsControl}
import event.XFormsEvents._
import org.orbeon.saxon.dom4j.DocumentWrapper
import org.orbeon.oxf.xml._
import java.util.{Map ⇒ JMap}
import org.orbeon.oxf.util.XPathCache
import java.lang.IllegalArgumentException
import org.dom4j._
import org.orbeon.scaxon.XML._
import collection.mutable.Buffer
import XXFormsDynamicControl._
import scala.None
import org.orbeon.oxf.xforms.event.{EventListener ⇒ JEventListener, XFormsEvent}
import scala.Predef._
import org.orbeon.oxf.xforms.event.events.{XXFormsValueChanged, XFormsDeleteEvent, XFormsInsertEvent}
import org.orbeon.oxf.xforms.XFormsConstants._
import org.orbeon.saxon.om.{VirtualNode, NodeInfo}

/**
 * xxforms:dynamic control
 *
 * This control must bind to an XHTML+XForms document held in an instance. The document is processed as an XForms
 * sub-document and handle as a shadow tree of xxforms:dynamic. Changes taking place in the document are dynamically
 * reported into the shadow tree.
 *
 * The following changes are handled specially:
 *
 * - changes to inline instance content on both sides are directly mirrored
 * - changes to content nested within top-level bound nodes cause re-evaluation of the binding only
 * - changes to nested binds cause incremental add/remove of binds
 *
 * All other changes cause the entire sub-document to be reprocessed.
 *
 * In the future the hope is to make any change fully incremental.
 */
class XXFormsDynamicControl(container: XBLContainer, parent: XFormsControl, element: Element, effectiveId: String, state: JMap[String, String])
    extends XFormsSingleNodeContainerControl(container, parent, element, effectiveId) {

    class Nested(val container: XBLContainer, val partAnalysis: PartAnalysisImpl, val template: SAXStore, val outerListener: JEventListener)

    private var _nested: Option[Nested] = None
    def nested = _nested

    private var previousChangeCount = -1
    private var changeCount = 0
    private val xblChanges = Buffer[(String, Element)]()
    private val bindChanges = Buffer[(String, Element)]()

    // New scripts created during an update (not functional as of 2012-04-19)
    // NOTE: This should instead be accumulated at the level of the request.
    private var _newScripts: Seq[Script] = Seq()
    def newScripts = _newScripts
    def clearNewScripts() = _newScripts = Seq()

    // TODO: This might blow if the control is non-relevant
    override def bindingContextForChild =
        _nested map { n ⇒
            val contextStack = n.container.getContextStack
            contextStack.setParentBindingContext(getBindingContext)
            contextStack.resetBindingContext()
            contextStack.getCurrentBindingContext
        } orNull

    override def onCreate(): Unit =
        getBoundElement match {
            case Some(node) ⇒ updateSubTree(node)
            case _ ⇒ // don't create binding (maybe we could for a read-only instance)
                _nested = None
        }

    override def onDestroy(): Unit = {
        // TODO: XXX remove child container from parent
        _nested = None
        previousChangeCount = 0
        changeCount = 0
    }

    override def onBindingUpdate(oldBinding: BindingContext, newBinding: BindingContext): Unit =
        getBoundElement match {
            case Some(node) ⇒ updateSubTree(node)
            case _ ⇒
        }

    private def updateSubTree(node: VirtualNode): Unit =
        if (previousChangeCount != changeCount) {
            // Document has changed and needs to be fully recreated
            processFullUpdate(node)
        } else {
            // Changes to nested binds
            if (bindChanges.nonEmpty)
                processBindsUpdates()

            // Changes to top-level XBL bindings
            if (xblChanges.nonEmpty)
                processXBLUpdates()
        }

    private def processFullUpdate(node: VirtualNode): Unit = {
        previousChangeCount = changeCount
        xblChanges.clear()
        bindChanges.clear()

        // Outer instance
        val outerInstance = containingDocument.getInstanceForNode(node)
        if (outerInstance eq null)
            throw new IllegalArgumentException

        // Remove children controls if any
        val tree = containingDocument.getControls.getCurrentControlTree
        if (getSize > 0) {
            // PERF: dispatching destruction events takes a lot of time, what can we do besides not dispatching them?
            //tree.dispatchDestructionEventsForRemovedContainer(this, false)
            tree.deindexSubtree(this, false)
            clearChildren()
        }

        _nested foreach { n ⇒
            // Remove container and associated models
            n.container.destroy()
            // Remove part and associated scopes
            containingDocument.getStaticOps.removePart(n.partAnalysis)
            // Remove listeners we added to the outer instance (better do this or we will badly leak)
            // WARNING: Make sure n.outerListener is the exact same object passed to addListener. There can be a
            // conversion from a function to a listener, in which case identity won't be preserved!
            InstanceMirror.removeListener(outerInstance, n.outerListener)
        }

        // Create new part
        val element = node.getUnderlyingNode.asInstanceOf[Element]
        val (template, partAnalysis) = createPartAnalysis(Dom4jUtils.createDocumentCopyElement(element), container.getPartAnalysis)

        // Update allowed events as depending on the dynamic subtree this can change
        tree.updateAllowedEvents(containingDocument)

        // Save new scripts if any
//            val newScriptCount = containingDocument.getStaticState.getScripts.size
//            if (newScriptCount > scriptCount)
//                newScripts = containingDocument.getStaticState.getScripts.values.slice(scriptCount, newScriptCount).toSeq
//            also addControlStructuralChange() if new scripts? or other update mechanism?

        // Nested container is initialized after binding and before control tree
        val childContainer = container.createChildContainer(this, partAnalysis)

        childContainer.addAllModels()
        childContainer.setLocationData(getLocationData)
        childContainer.initializeModels(Array(
            XFORMS_MODEL_CONSTRUCT,
            XFORMS_MODEL_CONSTRUCT_DONE)
        )

        // Add listener to the single outer instance
        val docWrapper = new DocumentWrapper(element.getDocument, null, XPathCache.getGlobalConfiguration)

        // NOTE: Make sure to convert to an EventListener so that addListener/removeListener deal with the exact same object
        val outerListener: JEventListener = {

            // Mark an unknown change, which will require a complete rebuild of the part
            val unknownChange: EventListener = { case _ ⇒
                changeCount += 1
                containingDocument.addControlStructuralChange(prefixedId)
                true
            }

            def recordChanges(findChange: NodeInfo ⇒ Option[(String, Element)], changes: Buffer[(String, Element)])(nodes: Seq[NodeInfo]): Boolean = {
                val newChanges = nodes flatMap (findChange(_))
                changes ++= newChanges
                newChanges.nonEmpty
            }

            def changeListener(record: Seq[NodeInfo] ⇒ Boolean): EventListener = {
                case insert: XFormsInsertEvent ⇒ record(insert.getInsertedItems.asScala collect { case n: NodeInfo ⇒ n })
                case delete: XFormsDeleteEvent ⇒ record(delete.deleteInfos.asScala map (_.nodeInfo))
                case valueChanged: XXFormsValueChanged ⇒ record(Seq(valueChanged.node))
                case _ ⇒ false
            }

            // Instance mirror listener
            val instanceListener = InstanceMirror.mirrorListener(
                containingDocument,
                getIndentedLogger,
                InstanceMirror.toInnerInstanceNode(docWrapper, partAnalysis, childContainer))

            // Compose listeners
            toJEventListener(composeListeners(Seq(
                instanceListener,
                changeListener(recordChanges(findXBLChange(partAnalysis, _), xblChanges)),
                changeListener(recordChanges(findBindChange, bindChanges)),
                unknownChange)))
        }

        InstanceMirror.addListener(outerInstance, outerListener)

        // Add mutation listeners to all top-level inline instances, which upon value change propagate the value
        // change to the related node in the source
        val innerListener: JEventListener = InstanceMirror.mirrorListener(
            containingDocument,
            getIndentedLogger,
            InstanceMirror.toOuterInstanceNode(docWrapper, partAnalysis))

        partAnalysis.getModelsForScope(partAnalysis.startScope).asScala foreach {
            _.instances.values filter (_.useInlineContent) foreach { instance ⇒
                val innerInstance = childContainer.findInstance(instance.staticId)
                InstanceMirror.addListener(innerInstance, innerListener)
            }
        }

        // Remember all that we created
        _nested = Some(new Nested(childContainer, partAnalysis, template, outerListener))

        // Create new control subtree
        tree.createAndInitializeSubTree(childContainer, this, partAnalysis.getTopLevelControls.head)
    }

    // If more than one change touches a given id, processed it once using the last element
    private def groupChanges(changes: Seq[(String, Element)]) =
        changes groupBy (_._1) mapValues (_ map (_._2) last) toList

    private def processXBLUpdates(): Unit = {

        val tree = containingDocument.getControls.getCurrentControlTree

        for ((prefixedId, element) ← groupChanges(xblChanges)) {
            // Get control
            val control = tree.getControl(prefixedId) // TODO: should use effective id if in repeat and process all

            control match {
                case componentControl: XFormsComponentControl ⇒

                    // Remove concrete models and controls
                    // PERF: dispatching destruction events takes a lot of time, what can we do besides not dispatching them?
                    // Also: check whether dispatchDestructionEventsForRemovedContainer dispatches to already non-relevant controls
                    //tree.dispatchDestructionEventsForRemovedContainer(componentControl, false)
                    componentControl.destroyNestedContainer()
                    componentControl.clearChildren()

                    // Remove static controls
                    tree.deindexSubtree(componentControl, false)

                    // Update the shadow tree
                    val staticComponent = _nested.get.partAnalysis.updateShadowTree(prefixedId, element)

                    // Create the new models and new concrete subtree rooted at xbl:template
                    componentControl.createNestedContainer()

                    val templateTree = staticComponent.children find (_.element.getQName == XBL_TEMPLATE_QNAME)
                    templateTree foreach
                        (tree.createAndInitializeSubTree(componentControl.nestedContainer, componentControl, _))

                    // Tell client
                    containingDocument.addControlStructuralChange(componentControl.prefixedId)
                case _ ⇒
            }
        }

        xblChanges.clear()
    }

    private def processBindsUpdates(): Unit = {

        val partAnalysis = _nested.get.partAnalysis

        for ((modelId, modelElement) ← groupChanges(bindChanges)) {

            val modelPrefixedId = partAnalysis.startScope.prefixedIdForStaticId(modelId)
            val staticModel = partAnalysis.getModel(modelPrefixedId)

            staticModel.rebuildBinds(modelElement)

            // Q: When should we best notify the concrete model that its binds need build? Since at this point, we
            // are within a bindings update, it would be nice if the binds are rebuilt before nested controls are
            // rebuilt below. However, it might not be safe to RRR right here. So for now, we just set the flag.
            val concreteModel = containingDocument.getObjectByEffectiveId(modelPrefixedId).asInstanceOf[XFormsModel]
            concreteModel.markStructuralChange(null) // NOTE: PathMapXPathDependencies doesn't yet make use of the `instance` parameter
        }

        bindChanges.clear()
   }

    private def getBoundElement =
        getBindingContext.getSingleItem match {
            case node: VirtualNode if node.getNodeKind == ELEMENT_NODE ⇒ Some(node)
            case _ ⇒ None
        }

    private def createPartAnalysis(doc: Document, parent: PartAnalysis) = {
        val newScope = new Scope(getResolutionScope, getPrefixedId)
        val (template, newPart) = XFormsStaticStateImpl.createPart(containingDocument.getStaticState, parent, doc, newScope)
        containingDocument.getStaticOps.addPart(newPart)
        (template, newPart)
    }

    override def getBackCopy = {
        val cloned = super.getBackCopy.asInstanceOf[XXFormsDynamicControl]
        cloned.previousChangeCount = -1 // unused
        cloned.changeCount = previousChangeCount
        cloned._nested = None

        cloned
    }

    // For now we don't need to output anything particular
    // LATER: what about new scripts?
    override def outputAjaxDiff(ch: ContentHandlerHelper, other: XFormsControl, attributesImpl: AttributesImpl, isNewlyVisibleSubtree: Boolean) = ()

    // Only if we had a structural change
    override def supportFullAjaxUpdates = containingDocument.getControlsStructuralChanges.contains(prefixedId)
}

object XXFormsDynamicControl {

    // Type of an event listener
    type EventListener = XFormsEvent ⇒ Boolean

    // Implicitly convert an EventListener to a Java EventListener
    implicit def toJEventListener(f: EventListener) = new JEventListener {
        def handleEvent(event: XFormsEvent) { f(event) }
    }

    // Compose a Seq of listeners by calling them in order until one has successfully processed the event
    def composeListeners(listeners: Seq[EventListener]): EventListener =
        e ⇒ listeners exists (_(e))

    // Find whether the given node is a bind element or attribute and return the associated model id → element mapping
    def findBindChange(node: NodeInfo): Option[(String, Element)] = {

        val XF = XFORMS_NAMESPACE_URI

        // XPath: ((self::xf:bind | self::@*/parent::xf:bind)/ancestor::xf:model)[1]
        val modelOption =
            (node self (XF → "bind")) ++ ((node self @* parent (XF → "bind"))) ancestor (XF → "model") headOption

        modelOption map { modelNode ⇒
            val modelElement = unwrapElement(modelNode)
            XFormsUtils.getElementId(modelElement) → modelElement
        }
    }

    // Find whether a change occurred in a descendant of a top-level XBL binding
    def findXBLChange(partAnalysis: PartAnalysis, node: NodeInfo): Option[(String, Element)] = {

        // Go from root to leaf
        val ancestorsFromRoot = node ancestor * reverse

        // Find first element whose prefixed id has a binding and return the mapping prefixedId → element
        val all =
            for {
                ancestor ← ancestorsFromRoot
                id = ancestor.attValue("id")
                if id.nonEmpty
                prefixedId = partAnalysis.startScope.prefixedIdForStaticId(id)
                binding ← partAnalysis.getBinding(prefixedId)
            } yield
                prefixedId → unwrapElement(ancestor)

        all.headOption
    }
}
