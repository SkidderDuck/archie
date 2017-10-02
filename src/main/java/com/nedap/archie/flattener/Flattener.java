package com.nedap.archie.flattener;

import com.google.common.collect.Lists;
import com.nedap.archie.aom.*;
import com.nedap.archie.aom.terminology.ArchetypeTerm;
import com.nedap.archie.aom.terminology.ArchetypeTerminology;
import com.nedap.archie.query.APathQuery;
import com.nedap.archie.rules.Assertion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

/**
 * Flattener. For single use only, create a new flattener for every flatten-action you want to do!
 *
 * TODO: the parent/child naming is very confusing. Make a new name. Original/specialized? Root/specialized? something else? Check the specs!

 *
 * Created by pieter.bos on 21/10/15.
 */
public class Flattener {

    //to be able to store Template Overlays transparently during flattening
    private OverridingArchetypeRepository repository;

    private Archetype parent;
    private Archetype child;

    private Archetype result;
    private boolean createOperationalTemplate = false;
    private boolean removeLanguagesFromMetaData = false;
    private boolean useComplexObjectForArchetypeSlotReplacement = false;

    private String[] languagesToKeep = null;

    private RulesFlattener rulesFlattener = new RulesFlattener();


    public Flattener(ArchetypeRepository repository) {
        this.repository = new OverridingArchetypeRepository(repository);
    }

    public Flattener createOperationalTemplate(boolean makeTemplate) {
        this.createOperationalTemplate = makeTemplate;
        return this;
    }

    /**
     * if this flattener is setup to create operational templates, also set it to remove all languages from the terminology
     * except for the given languages
     * @param languages
     * @return
     */
    public Flattener keepLanguages(String... languages) {
        languagesToKeep = languages;
        return this;
    }

    public Flattener removeLanguagesFromMetadata(boolean remove) {
        this.removeLanguagesFromMetaData = remove;
        return this;
    }

    public Archetype flatten(Archetype toFlatten) {
        if(parent != null) {
            throw new IllegalStateException("You've used this flattener before - single use instance, please create a new one!");
        }
        //validate that we can legally flatten first
        String parentId = toFlatten.getParentArchetypeId();
        if(parentId == null) {
            if(createOperationalTemplate) {
                OperationalTemplate template = createOperationalTemplate(toFlatten);
                result = template;
                //make an operational template by just filling complex object proxies and archetype slots
                fillSlots(template);
                TerminologyFlattener.filterLanguages(template, removeLanguagesFromMetaData, languagesToKeep);
                result = template;
            } else {
                result = toFlatten.clone();
            }
            result.getDefinition().setArchetype(result);
            return result;
        }

        this.parent = repository.getArchetype(toFlatten.getParentArchetypeId());
        if(parent == null) {
            throw new IllegalArgumentException("parent archetype not found in repository: " + toFlatten.getParentArchetypeId());
        }
        this.child = toFlatten.clone();//just to be sure, so we don't have to copy more things deeper down


        if(child instanceof Template) {
            Template childTemplate = (Template) child;
            for(TemplateOverlay overlay:childTemplate.getTemplateOverlays()) {
                //we'll flatten them later when we need them, otherwise, you run into problems with archetypes
                //not yet added to repository while we already need them
                repository.addArchetype(overlay);
            }
        }

        if(parent.getParentArchetypeId() != null) {
            //parent needs flattening first
            parent = getNewFlattener().flatten(parent);
        }


        this.result = null;
        if(createOperationalTemplate) {
            result = createOperationalTemplate(parent);
            overrideArchetypeId(result, child);
        } else {
            result = parent.clone();
        }

        //1. redefine structure
        //2. fill archetype slots if we are creating an operational template
        flatten(result, child);

        rulesFlattener.combineRules(child, result, "prefix", "", "", true /* override statements with same tag */);//TODO: actually set a unique prefix
        if(createOperationalTemplate) {
            fillSlots(result);

        }
        TerminologyFlattener.flattenTerminology(result, child);

        if(createOperationalTemplate) {
            TerminologyFlattener.filterLanguages((OperationalTemplate) result, removeLanguagesFromMetaData, languagesToKeep);
        }
        result.getDefinition().setArchetype(result);
        return result;
    }


    public void fillSlots(Archetype archetype) { //should this be OperationalTemplate?
        fillComplexObjectProxies(archetype);
        closeArchetypeSlots(archetype);
        fillArchetypeRoots(archetype);
        removeZeroOccurrencesConstraints(archetype);

    }

    private void removeZeroOccurrencesConstraints(Archetype archetype) {
        Stack<CObject> workList = new Stack<>();
        workList.push(archetype.getDefinition());
        while(!workList.isEmpty()) {
            CObject object = workList.pop();
            List<CAttribute> attributesToRemove = new ArrayList<>();
            for(CAttribute attribute:object.getAttributes()) {
                if(attribute.getExistence() != null && attribute.getExistence().getUpper() == 0 && !attribute.getExistence().isUpperUnbounded()) {
                    attributesToRemove.add(attribute);
                } else {
                    List<CObject> objectsToRemove = new ArrayList<>();
                    for (CObject child : attribute.getChildren()) {
                        if (!child.isAllowed()) {
                            objectsToRemove.add(child);
                        }
                        workList.push(child);
                    }
                    attribute.getChildren().removeAll(objectsToRemove);
                }

            }
            object.getAttributes().removeAll(attributesToRemove);
        }
    }

    private void closeArchetypeSlots(Archetype archetype) {
        Stack<CObject> workList = new Stack<>();
        workList.push(archetype.getDefinition());
        while(!workList.isEmpty()) {
            CObject object = workList.pop();
            for(CAttribute attribute:object.getAttributes()) {
                List<CObject> toRemove = new ArrayList<>();
                for(CObject child:attribute.getChildren()) {
                    if(child instanceof ArchetypeSlot) { //use_archetype
                        if(((ArchetypeSlot) child).isClosed()) {
                            toRemove.add(child);
                        }
                    }
                    workList.push(child);
                }
                attribute.getChildren().removeAll(toRemove);
            }
        }
    }

    private void fillArchetypeRoots(Archetype result) {

        Stack<CObject> workList = new Stack<>();
        workList.push(result.getDefinition());
        while(!workList.isEmpty()) {
            CObject object = workList.pop();
            for(CAttribute attribute:object.getAttributes()) {
                for(CObject child:attribute.getChildren()) {
                    if(child instanceof CArchetypeRoot) { //use_archetype
                        fillArchetypeRoot((CArchetypeRoot) child);
                    }
                    workList.push(child);
                }
            }
        }
    }

    private void fillComplexObjectProxies(Archetype result) {

        Stack<CObject> workList = new Stack<>();
        workList.push(result.getDefinition());
        List<ComplexObjectProxyReplacement> replacements = new ArrayList<>();
        while(!workList.isEmpty()) {
            CObject object = workList.pop();
            for(CAttribute attribute:object.getAttributes()) {
                for(CObject child:attribute.getChildren()) {
                    if(child instanceof CComplexObjectProxy) { //use_node
                        ComplexObjectProxyReplacement possibleReplacement = getComplexObjectProxyReplacement((CComplexObjectProxy) child);
                        if(possibleReplacement != null) {
                            replacements.add(possibleReplacement);
                        }

                    }
                    workList.push(child);
                }
            }
        }
        for(ComplexObjectProxyReplacement replacement:replacements) {
            replacement.replace();
        }
    }

    private ComplexObjectProxyReplacement getComplexObjectProxyReplacement(CComplexObjectProxy proxy) {
        if(createOperationalTemplate) {
            CObject newObject = new APathQuery(proxy.getTargetPath()).find(proxy.getArchetype().getDefinition());
            if(newObject == null) {
                throw new RuntimeException("cannot find target in CComplexObjectProxy");
            } else {
                CComplexObject clone = (CComplexObject) newObject.clone();
                clone.setNodeId(proxy.getNodeId());
                return new ComplexObjectProxyReplacement(proxy, clone);

            }
        }
        return null;
    }

    /**
     * little class used for a CompelxObjectProxyReplacement because we cannot replace in a collection
     * that we iterate at the same time
     */
    private static class ComplexObjectProxyReplacement {
        private final CComplexObject object;
        private final CComplexObjectProxy proxy;

        public ComplexObjectProxyReplacement(CComplexObjectProxy proxy, CComplexObject object) {
            this.proxy = proxy;
            this.object = object;
        }

        public void replace() {
            proxy.getParent().replaceChild(proxy.getNodeId(), object);
        }
    }

    private void fillArchetypeRoot(CArchetypeRoot root) {
        if(createOperationalTemplate) {
            String archetypeRef = root.getArchetypeRef();
            String newArchetypeRef = archetypeRef;
            Archetype archetype = this.repository.getArchetype(archetypeRef);
            if(archetype instanceof TemplateOverlay){
                //we want to be able to check which archetype this is in the UI. If it's an overlay, that means retrieving the non-operational template
                //which is a hassle.
                //That's a problem. Is this the way to fix is?
                newArchetypeRef = archetype.getParentArchetypeId();
            }
            if (archetype == null) {
                throw new IllegalArgumentException("Archetype with reference :" + archetypeRef + " not found.");
            }

            archetype = getNewFlattener().flatten(archetype);

            //
            CComplexObject rootToFill = root;
            if(useComplexObjectForArchetypeSlotReplacement) {
                rootToFill = archetype.getDefinition();
                root.getParent().replaceChild(root.getNodeId(), rootToFill);
            } else {
                rootToFill.setAttributes(archetype.getDefinition().getAttributes());
                rootToFill.setAttributeTuples(archetype.getDefinition().getAttributeTuples());
                rootToFill.setDefaultValue(archetype.getDefinition().getDefaultValue());
            }
            String newNodeId = archetype.getArchetypeId().getFullId();

            ArchetypeTerminology terminology = archetype.getTerminology();

            //The node id will be replaced from "id1" to something like "openEHR-EHR-COMPOSITION.template_overlay.v1.0.0
            //so store it in the terminology as well
            //TODO: value sets, term bindings, etc.
            Map<String, Map<String, ArchetypeTerm>> termDefinitions = terminology.getTermDefinitions();

            for(String language: termDefinitions.keySet()) {
                Map<String, ArchetypeTerm> translations = termDefinitions.get(language);
                translations.put(newNodeId, TerminologyFlattener.getTerm(terminology.getTermDefinitions(), language, archetype.getDefinition().getNodeId()));
            }


            //TODO: if someone adds for example id1.1, but does not translate it, should we automatically add it to the terminology?

            //rootToFill.setNodeId(newNodeId);
            if(!useComplexObjectForArchetypeSlotReplacement) {
                root.setArchetypeRef(newNodeId);
            }
            OperationalTemplate templateResult = (OperationalTemplate) result;

            //todo: should we filter this?
            if(archetype instanceof OperationalTemplate) {
                OperationalTemplate template = (OperationalTemplate) archetype;
                //add all the component terminologies, otherwise we lose translation
                for(String subarchetypeId:template.getComponentTerminologies().keySet()) {
                    templateResult.addComponentTerminology(subarchetypeId, template.getComponentTerminologies().get(subarchetypeId));
                }
            }

            templateResult.addComponentTerminology(newNodeId, terminology);

            String prefix = archetype.getArchetypeId().getConceptId() + "_";
            rulesFlattener.combineRules(archetype, root.getArchetype(), prefix, prefix, rootToFill.getPath(), false); //TODO: add prefixes to make them unique, or some other way of making unique
            //todo: do we have to put something in the terminology extracts?
            //templateResult.addTerminologyExtract(child.getNodeId(), archetype.getTerminology().);
        }

    }





    private static OperationalTemplate createOperationalTemplate(Archetype archetype) {
        Archetype toClone = archetype.clone(); //clone so we do not overwrite the parent archetype. never
        OperationalTemplate result = new OperationalTemplate();
        result.setArchetypeId((ArchetypeHRID) archetype.getArchetypeId().clone());
        result.setDefinition(toClone.getDefinition());
        result.setDifferential(false);

        result.setRmRelease(toClone.getRmRelease());
        result.setAdlVersion(toClone.getAdlVersion());
        result.setTerminology(toClone.getTerminology());
        result.setGenerated(true);
        result.setOtherMetaData(toClone.getOtherMetaData());
        result.setRules(toClone.getRules());
        result.setBuildUid(toClone.getBuildUid());
        result.setDescription(toClone.getDescription());
        result.setOriginalLanguage(toClone.getOriginalLanguage());
        result.setTranslations(toClone.getTranslations());

        return result;
    }

    private static void overrideArchetypeId(Archetype result, Archetype override) {
        result.setArchetypeId(override.getArchetypeId());
        result.setParentArchetypeId(override.getParentArchetypeId());
    }

    private void flatten(Archetype parent, Archetype specialized) {
        parent.setArchetypeId(specialized.getArchetypeId()); //TODO: override all metadata?
        flattenCObject(null, parent.getDefinition(), Lists.newArrayList(specialized.getDefinition()));

    }

    private void flattenCObject(CAttribute attribute, CObject parent, List<CObject> specializedList) {
        List<CObject> newNodes = new ArrayList<>();
        for(CObject specialized:specializedList) {
            CObject newObject = cloneSpecializedObject(attribute, parent, specialized);

            specializeOccurrences(specialized, newObject);
            newObject.setSiblingOrder(getPossiblyOverridenValue(newObject.getSiblingOrder(), specialized.getSiblingOrder()));

            newObject.setNodeId(getPossiblyOverridenValue(newObject.getNodeId(), specialized.getNodeId()));
            newObject.setRmTypeName(getPossiblyOverridenValue(newObject.getRmTypeName(), specialized.getRmTypeName()));

            //now specialize the structure under the specialized node
            specializeContent(parent, specialized, newObject);

            newNodes.add(newObject);
        }

        if(attribute != null && !newNodes.isEmpty()) {
            boolean shouldReplaceParent = shouldReplaceParent(parent, newNodes);
            attribute.replaceChildren(parent.getNodeId(), newNodes, !shouldReplaceParent /* remove original */);
        }

    }

    private void specializeContent(CObject parent, CObject specialized, CObject newObject) {
        if (parent instanceof CComplexObject) {
            flattenCComplexObject((CComplexObject) newObject, (CComplexObject) specialized);
        }
        else if (newObject instanceof ArchetypeSlot) {//archetypeslot is NOT a complex object. It's replacement can be
            if(specialized instanceof ArchetypeSlot) {
                flattenArchetypeSlot((ArchetypeSlot) newObject, (ArchetypeSlot) specialized);
            } else if(specialized instanceof CArchetypeRoot) {
                //TODO: handle as if this is a template overlay, but inline. Probably needed in the fillArchetypeRoot method, not here?
            } else {
                throw new IllegalArgumentException("Can only replace an archetype slot with an archetype root or another archetype slot, not with a " + newObject.getClass());
            }
        }
    }

    private void specializeOccurrences(CObject specialized, CObject newObject) {
        //TODO: check if overriding occurrences is allowed
        newObject.setOccurrences(getPossiblyOverridenValue(newObject.getOccurrences(), specialized.getOccurrences()));
    }

    private CObject cloneSpecializedObject(CAttribute attribute, CObject parent, CObject specialized) {
        CObject newObject;
        if(attribute == null) {
            //root of archetype. don't clone anything.. alternative: make a mock attribute at the root
            newObject = parent;
        } else {
            newObject = (CObject) parent.clone();
        }
        if(newObject instanceof ArchetypeSlot && specialized instanceof CArchetypeRoot) {
            newObject = (CObject) specialized.clone();
        }
        return newObject;
    }

    private boolean shouldReplaceParent(CObject parent, List<CObject> differentialNodes) {
        for(CObject differentialNode: differentialNodes) {
            if(differentialNode.getNodeId().equals(parent.getNodeId())) {
                //same node id, so no specialization
                return true;
            }
        }
        if(parent.getOccurrences() != null && parent.getOccurrences().upperIsOne()) {
            //REFINE the parent node case 1, the parent has occurrences upper == 1
            return true;
        } else if (differentialNodes.size() == 1
                && differentialNodes.get(0).getOccurrences() != null
                && differentialNodes.get(0).getOccurrences().upperIsOne()) {
            //REFINE the parent node case 2, only one child with occurrences upper == 1
            return true;
        }
        return false;
    }

    private void flattenArchetypeSlot(ArchetypeSlot parent, ArchetypeSlot specialized) {
        if(specialized.isClosed()) {
            parent.setClosed(true);
        }
        parent.setIncludes(getPossiblyOverridenListValue(parent.getIncludes(), specialized.getIncludes()));
        parent.setExcludes(getPossiblyOverridenListValue(parent.getExcludes(), specialized.getExcludes()));

        //TODO: includes/excludes?
    }

    /**
     * Flatten a CComplexObject. newObject must be a clone of the original parent, specialized the original unmodified
     * specialized node.
     *
     * The attributes of newObject will be changed in place, so newObject will be altered in this operation
     *
     * @param newObject
     * @param specialized
     */
    private void flattenCComplexObject(CComplexObject newObject, CComplexObject specialized) {

        for(CAttribute attribute:specialized.getAttributes()) {
            if(attribute.getDifferentialPath() != null) {
                //this overrides a specific path
                ArchetypeModelObject object = new APathQuery(attribute.getDifferentialPath()).find(newObject);
                if(object instanceof CAttribute) {
                    CAttribute realAttribute = (CAttribute) object;
                    flattenAttribute(newObject, realAttribute, attribute);
                } else if (object instanceof CObject) {
                    //TODO: what does this mean?
                }

            } else {
                //this overrides the same path
                flattenAttribute(newObject, newObject.getAttribute(attribute.getRmAttributeName()), attribute);
            }
        }
    }

    private Flattener getNewFlattener() {
        return new Flattener(repository).createOperationalTemplate(createOperationalTemplate).useComplexObjectForArchetypeSlotReplacement(useComplexObjectForArchetypeSlotReplacement);
    }

    private Flattener useComplexObjectForArchetypeSlotReplacement(boolean useComplexObjectForArchetypeSlotReplacement) {
        this.useComplexObjectForArchetypeSlotReplacement = useComplexObjectForArchetypeSlotReplacement;
        return this;
    }


    private void flattenAttribute(CComplexObject root, CAttribute parent, CAttribute specialized) {
        if(parent == null) {
            CAttribute childCloned = specialized.clone();
            root.addAttribute(childCloned);
        } else {
            parent.setMultiple(getPossiblyOverridenValue(parent.isMultiple(), specialized.isMultiple()));
            parent.setExistence(getPossiblyOverridenValue(parent.getExistence(), specialized.getExistence()));
            parent.setCardinality(getPossiblyOverridenValue(parent.getCardinality(), specialized.getCardinality()));

            if (specialized.getChildren().size() > 0 && specialized.getChildren().get(0) instanceof CPrimitiveObject) {
                //TODO: is this correct? replace all specialized nodes
                parent.setChildren(specialized.getChildren());
            } else {

                Set<CObject> newChildObjects = new LinkedHashSet<>(specialized.getChildren());
                for (CObject parentCObject : new ArrayList<>(parent.getChildren())) {
                    List<CObject> matchingChildren = new ArrayList<>();
                    for (CObject specializedChildCObject : specialized.getChildren()) {

                        if (isOverridenCObject(specializedChildCObject, parentCObject)) {
                            matchingChildren.add(specializedChildCObject);
                            newChildObjects.remove(specializedChildCObject);
                        }

                    }
                    flattenCObject(parent, parentCObject, matchingChildren);
                }

                for(CObject newChild:newChildObjects) {
                    //TODO: check that newChildObjects start with id0. at the correct specialization level
                    parent.addChild(newChild);
                }
            }
        }

    }

    private boolean isOverridenCObject(CObject specialized, CObject parent) {
        String specializedNodeId = specialized.getNodeId();
        if(specializedNodeId.lastIndexOf('.') > 0) {
            specializedNodeId = specializedNodeId.substring(0, specializedNodeId.lastIndexOf('.'));//-1?
        }
        return specializedNodeId.equals(parent.getNodeId()) || specializedNodeId.startsWith(parent.getNodeId() + ".");
    }

    private List<Assertion> getPossiblyOverridenListValue(List<Assertion> parent, List<Assertion> child) {
        if(child != null && !child.isEmpty()) {
            return child;
        }
        return parent;
    }

    public <T> T getPossiblyOverridenValue(T parent, T specialized) {
        if(specialized != null) {
            return specialized;
        }
        return parent;
    }


}
