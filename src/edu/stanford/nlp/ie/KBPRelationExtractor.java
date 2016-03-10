package edu.stanford.nlp.ie;


import edu.stanford.nlp.classify.*;
import edu.stanford.nlp.ie.machinereading.structure.Span;
import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.Datum;
import edu.stanford.nlp.ling.RVFDatum;
import edu.stanford.nlp.optimization.*;
import edu.stanford.nlp.pipeline.CoreNLPProtos;
import edu.stanford.nlp.pipeline.ProtobufAnnotationSerializer;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.simple.Document;
import edu.stanford.nlp.simple.Sentence;
import edu.stanford.nlp.stats.ClassicCounter;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.stats.Counters;
import edu.stanford.nlp.util.*;
import edu.stanford.nlp.util.logging.Redwood;
import edu.stanford.nlp.util.logging.RedwoodConfiguration;

import java.io.*;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static edu.stanford.nlp.util.logging.Redwood.Util.*;
import static edu.stanford.nlp.ie.KBPRelationExtractor.NERTag.*;

/**
 * A relation extractor to work with Victor's new KBP data.
 */
@SuppressWarnings("FieldCanBeLocal")
public class KBPRelationExtractor implements Serializable {
  private static final long serialVersionUID = 1L;

  @ArgumentParser.Option(name="train", gloss="The dataset to train on")
  public static File TRAIN_FILE = new File("train.conll");

  @ArgumentParser.Option(name="test", gloss="The dataset to test on")
  public static File TEST_FILE = new File("test.conll");

  @ArgumentParser.Option(name="model", gloss="The dataset to test on")
  public static String MODEL_FILE = "model.ser";

  private enum MinimizerType{ QN, SGD, HYBRID, L1 }
  @ArgumentParser.Option(name="minimizer", gloss="The minimizer to use for training the classifier")
  private static MinimizerType minimizer = MinimizerType.L1;

  @ArgumentParser.Option(name="feature_threshold", gloss="The minimum number of times to see a feature to count it")
  private static int FEATURE_THRESHOLD = 0;

  @ArgumentParser.Option(name="sigma", gloss="The regularizer for the classifier")
  private static double SIGMA = 1.0;

  public static final String NO_RELATION = "no_relation";

  private static final Redwood.RedwoodChannels log = Redwood.channels(KBPRelationExtractor.class);

  @SuppressWarnings("unused")
  public static class FeaturizerInput {

    public final Span subjectSpan;
    public final Span objectSpan;
    public final NERTag subjectType;
    public final NERTag objectType;
    public final Sentence sentence;

    public FeaturizerInput(Span subjectSpan, Span objectSpan,
                           NERTag subjectType, NERTag objectType,
                           Sentence sentence) {
      this.subjectSpan = subjectSpan;
      this.objectSpan = objectSpan;
      this.subjectType = subjectType;
      this.objectType = objectType;
      this.sentence = sentence;
    }

    public Sentence getSentence() {
      return sentence;
    }

    public Span getSubjectSpan() {
      return subjectSpan;
    }

    public String getSubjectText() {
      return StringUtils.join(sentence.originalTexts().subList(subjectSpan.start(), subjectSpan.end()).stream(), " ");
    }

    public Span getObjectSpan() {
      return objectSpan;
    }

    public String getObjectText() {
      return StringUtils.join(sentence.originalTexts().subList(objectSpan.start(), objectSpan.end()).stream(), " ");
    }

    @Override
    public String toString() {
      return "FeaturizerInput{" +
          ", subjectSpan=" + subjectSpan +
          ", objectSpan=" + objectSpan +
          ", sentence=" + sentence +
          '}';
    }
  }

  /**
   * A list of valid KBP NER tags.
   */
  public enum NERTag {
    // ENUM_NAME        NAME           SHORT_NAME  IS_REGEXNER_TYPE
    CAUSE_OF_DEATH("CAUSE_OF_DEATH", "COD", true), // note: these names must be upper case
    CITY("CITY", "CIT", true), //       furthermore, DO NOT change the short names, or else serialization may break
    COUNTRY("COUNTRY", "CRY", true),
    CRIMINAL_CHARGE("CRIMINAL_CHARGE", "CC", true),
    DATE("DATE", "DT", false),
    IDEOLOGY("IDEOLOGY", "IDY", true),
    LOCATION("LOCATION", "LOC", false),
    MISC("MISC", "MSC", false),
    MODIFIER("MODIFIER", "MOD", false),
    NATIONALITY("NATIONALITY", "NAT", true),
    NUMBER("NUMBER", "NUM", false),
    ORGANIZATION("ORGANIZATION", "ORG", false),
    PERSON("PERSON", "PER", false),
    RELIGION("RELIGION", "REL", true),
    STATE_OR_PROVINCE("STATE_OR_PROVINCE", "ST", true),
    TITLE("TITLE", "TIT", true),
    URL("URL", "URL", true),
    DURATION("DURATION", "DUR", false),
    GPE("GPE", "GPE", false), // note(chaganty): This NER tag is solely used in the cold-start system for entities.
//  SCHOOL            ("SCHOOL",            "SCH", true),
    ;

    /**
     * The full name of this NER tag, as would come out of our NER or RegexNER system
     */
    public final String name;
    /**
     * A short name for this NER tag, intended for compact serialization
     */
    public final String shortName;
    /**
     * If true, this NER tag is not in the standard NER set, but is annotated via RegexNER
     */
    public final boolean isRegexNERType;

    NERTag(String name, String shortName, boolean isRegexNERType) {
      this.name = name;
      this.shortName = shortName;
      this.isRegexNERType = isRegexNERType;
    }

    /** Find the slot for a given name */
    public static Optional<NERTag> fromString(String name) {
      // Early termination
      if (name == null || name.equals("")) { return Optional.empty(); }
      // Cycle known NER tags
      name = name.toUpperCase();
      for (NERTag slot : NERTag.values()) {
        if (slot.name.equals(name)) return Optional.of(slot);
      }
      for (NERTag slot : NERTag.values()) {
        if (slot.shortName.equals(name)) return Optional.of(slot);
      }
      // Some quick fixes
      return Optional.empty();
    }
  }


  /**
   * Known relation types (last updated for the 2013 shared task).
   *
   * Note that changing the constants here can have far-reaching consequences in loading serialized
   * models, and various bits of code that have been hard-coded to these relation types (e.g., the various
   * consistency filters). <b>If you'd like to change only the output form of these relations.
   *
   * <p>
   * NOTE: Neither per:spouse, org:founded_by, or X:organizations_founded are SINGLE relations
   *       in the spec -- these are made single here
   *       because our system otherwise over-predicts them.
   * </p>
   *
   * @author Gabor Angeli
   */
  public enum RelationType {
    PER_ALTERNATE_NAMES("per:alternate_names", true, 10, NERTag.PERSON, Cardinality.LIST, new NERTag[]{PERSON, MISC}, new String[]{"NNP"}, 0.0353027270308107100),
    PER_CHILDREN("per:children", true, 5, NERTag.PERSON, Cardinality.LIST, new NERTag[]{PERSON}, new String[]{"NNP"}, 0.0058428110284504410),
    PER_CITIES_OF_RESIDENCE("per:cities_of_residence", true, 5, NERTag.PERSON, Cardinality.LIST, new NERTag[]{CITY,}, new String[]{"NNP"}, 0.0136105679675116560),
    PER_CITY_OF_BIRTH("per:city_of_birth", true, 3, NERTag.PERSON, Cardinality.SINGLE, new NERTag[]{CITY,}, new String[]{"NNP"}, 0.0358146961159769100),
    PER_CITY_OF_DEATH("per:city_of_death", true, 3, NERTag.PERSON, Cardinality.SINGLE, new NERTag[]{CITY,}, new String[]{"NNP"}, 0.0102003332137774650),
    PER_COUNTRIES_OF_RESIDENCE("per:countries_of_residence", true, 5, NERTag.PERSON, Cardinality.LIST, new NERTag[]{COUNTRY,}, new String[]{"NNP"}, 0.0107788293552082020),
    PER_COUNTRY_OF_BIRTH("per:country_of_birth", true, 3, NERTag.PERSON, Cardinality.SINGLE, new NERTag[]{COUNTRY,}, new String[]{"NNP"}, 0.0223444134627622040),
    PER_COUNTRY_OF_DEATH("per:country_of_death", true, 3, NERTag.PERSON, Cardinality.SINGLE, new NERTag[]{COUNTRY,}, new String[]{"NNP"}, 0.0060626395621941200),
    PER_EMPLOYEE_OF("per:employee_of", true, 10, NERTag.PERSON, Cardinality.LIST, new NERTag[]{ORGANIZATION, COUNTRY, STATE_OR_PROVINCE, CITY}, new String[]{"NNP"}, 2.0335281901169719200),
    PER_LOC_OF_BIRTH("per:LOCATION_of_birth", true, 3, NERTag.PERSON, Cardinality.LIST, new NERTag[]{CITY, STATE_OR_PROVINCE, COUNTRY}, new String[]{"NNP"}, 0.0165825918941120660),
    PER_LOC_OF_DEATH("per:LOCATION_of_death", true, 3, NERTag.PERSON, Cardinality.LIST, new NERTag[]{CITY, STATE_OR_PROVINCE, COUNTRY}, new String[]{"NNP"}, 0.0165825918941120660),
    PER_LOC_OF_RESIDENCE("per:LOCATION_of_residence", true, 3, NERTag.PERSON, Cardinality.LIST, new NERTag[]{STATE_OR_PROVINCE,}, new String[]{"NNP"}, 0.0165825918941120660),
    PER_MEMBER_OF("per:member_of", true, 10, NERTag.PERSON, Cardinality.LIST, new NERTag[]{ORGANIZATION}, new String[]{"NNP"}, 0.0521716745149309900),
    PER_ORIGIN("per:origin", true, 10, NERTag.PERSON, Cardinality.LIST, new NERTag[]{NATIONALITY, COUNTRY}, new String[]{"NNP"}, 0.0069795559463618380),
    PER_OTHER_FAMILY("per:other_family", true, 5, NERTag.PERSON, Cardinality.LIST, new NERTag[]{PERSON}, new String[]{"NNP"}, 2.7478566717959990E-5),
    PER_PARENTS("per:parents", true, 5, NERTag.PERSON, Cardinality.LIST, new NERTag[]{PERSON}, new String[]{"NNP"}, 0.0032222235077692030),
    PER_SCHOOLS_ATTENDED("per:schools_attended", true, 5, NERTag.PERSON, Cardinality.LIST, new NERTag[]{ORGANIZATION}, new String[]{"NNP"}, 0.0054696810172276150),
    PER_SIBLINGS("per:siblings", true, 5, NERTag.PERSON, Cardinality.LIST, new NERTag[]{PERSON}, new String[]{"NNP"}, 1.000000000000000e-99),
    PER_SPOUSE("per:spouse", true, 3, NERTag.PERSON, Cardinality.LIST, new NERTag[]{PERSON}, new String[]{"NNP"}, 0.0164075968113292680),
    PER_STATE_OR_PROVINCES_OF_BIRTH("per:stateorprovince_of_birth", true, 3, NERTag.PERSON, Cardinality.SINGLE, new NERTag[]{STATE_OR_PROVINCE,}, new String[]{"NNP"}, 0.0165825918941120660),
    PER_STATE_OR_PROVINCES_OF_DEATH("per:stateorprovince_of_death", true, 3, NERTag.PERSON, Cardinality.SINGLE, new NERTag[]{STATE_OR_PROVINCE,}, new String[]{"NNP"}, 0.0050083303444366030),
    PER_STATE_OR_PROVINCES_OF_RESIDENCE("per:stateorprovinces_of_residence", true, 5, NERTag.PERSON, Cardinality.LIST, new NERTag[]{STATE_OR_PROVINCE,}, new String[]{"NNP"}, 0.0066787379528178550),
    PER_AGE("per:age", true, 3, NERTag.PERSON, Cardinality.SINGLE, new NERTag[]{NUMBER, DURATION}, new String[]{"CD", "NN"}, 0.0483159977322951300),
    PER_DATE_OF_BIRTH("per:date_of_birth", true, 3, NERTag.PERSON, Cardinality.SINGLE, new NERTag[]{DATE}, new String[]{"CD", "NN"}, 0.0743584477791533200),
    PER_DATE_OF_DEATH("per:date_of_death", true, 3, NERTag.PERSON, Cardinality.SINGLE, new NERTag[]{DATE}, new String[]{"CD", "NN"}, 0.0189819046406960460),
    PER_CAUSE_OF_DEATH("per:cause_of_death", true, 3, NERTag.PERSON, Cardinality.SINGLE, new NERTag[]{CAUSE_OF_DEATH}, new String[]{"NN"}, 1.0123682475037891E-5),
    PER_CHARGES("per:charges", true, 5, NERTag.PERSON, Cardinality.LIST, new NERTag[]{CRIMINAL_CHARGE}, new String[]{"NN"}, 3.8614617440501670E-4),
    PER_RELIGION("per:religion", true, 3, NERTag.PERSON, Cardinality.SINGLE, new NERTag[]{RELIGION}, new String[]{"NN"}, 7.6650738739572610E-4),
    PER_TITLE("per:title", true, 15, NERTag.PERSON, Cardinality.LIST, new NERTag[]{TITLE, MODIFIER}, new String[]{"NN"}, 0.0334283995325751200),
    ORG_ALTERNATE_NAMES("org:alternate_names", true, 10, NERTag.ORGANIZATION, Cardinality.LIST, new NERTag[]{ORGANIZATION, MISC}, new String[]{"NNP"}, 0.0552058867767352000),
    ORG_CITY_OF_HEADQUARTERS("org:city_of_headquarters", true, 3, NERTag.ORGANIZATION, Cardinality.SINGLE, new NERTag[]{CITY, LOCATION}, new String[]{"NNP"}, 0.0555949254318473740),
    ORG_COUNTRY_OF_HEADQUARTERS("org:country_of_headquarters", true, 3, NERTag.ORGANIZATION, Cardinality.SINGLE, new NERTag[]{COUNTRY, NATIONALITY}, new String[]{"NNP"}, 0.0580217167451493100),
    ORG_FOUNDED_BY("org:founded_by", true, 3, NERTag.ORGANIZATION, Cardinality.LIST, new NERTag[]{PERSON, ORGANIZATION}, new String[]{"NNP"}, 0.0050806423621154450),
    ORG_LOC_OF_HEADQUARTERS("org:LOCATION_of_headquarters", true, 10, NERTag.ORGANIZATION, Cardinality.LIST, new NERTag[]{CITY, STATE_OR_PROVINCE, COUNTRY,}, new String[]{"NNP"}, 0.0555949254318473740),
    ORG_MEMBER_OF("org:member_of", true, 20, NERTag.ORGANIZATION, Cardinality.LIST, new NERTag[]{ORGANIZATION, STATE_OR_PROVINCE, COUNTRY,}, new String[]{"NNP"}, 0.0396298781687126140),
    ORG_MEMBERS("org:members", true, 20, NERTag.ORGANIZATION, Cardinality.LIST, new NERTag[]{ORGANIZATION, COUNTRY}, new String[]{"NNP"}, 0.0012220730987724312),
    ORG_PARENTS("org:parents", true, 10, NERTag.ORGANIZATION, Cardinality.LIST, new NERTag[]{ORGANIZATION,}, new String[]{"NNP"}, 0.0550048593675880200),
    ORG_POLITICAL_RELIGIOUS_AFFILIATION("org:political/religious_affiliation", true, 5, NERTag.ORGANIZATION, Cardinality.LIST, new NERTag[]{IDEOLOGY, RELIGION}, new String[]{"NN", "JJ"}, 0.0059266929689578970),
    ORG_SHAREHOLDERS("org:shareholders", true, 10, NERTag.ORGANIZATION, Cardinality.LIST, new NERTag[]{PERSON, ORGANIZATION}, new String[]{"NNP"}, 1.1569922828614734E-5),
    ORG_STATE_OR_PROVINCES_OF_HEADQUARTERS("org:stateorprovince_of_headquarters", true, 3, NERTag.ORGANIZATION, Cardinality.SINGLE, new NERTag[]{STATE_OR_PROVINCE}, new String[]{"NNP"}, 0.0312619314829170100),
    ORG_SUBSIDIARIES("org:subsidiaries", true, 20, NERTag.ORGANIZATION, Cardinality.LIST, new NERTag[]{ORGANIZATION}, new String[]{"NNP"}, 0.0162412791706679320),
    ORG_TOP_MEMBERS_SLASH_EMPLOYEES("org:top_members/employees", true, 10, NERTag.ORGANIZATION, Cardinality.LIST, new NERTag[]{PERSON}, new String[]{"NNP"}, 0.0907168724184609800),
    ORG_DISSOLVED("org:dissolved", true, 3, NERTag.ORGANIZATION, Cardinality.SINGLE, new NERTag[]{DATE}, new String[]{"CD", "NN"}, 0.0023877428237553656),
    ORG_FOUNDED("org:founded", true, 3, NERTag.ORGANIZATION, Cardinality.SINGLE, new NERTag[]{DATE}, new String[]{"CD", "NN"}, 0.0796314401082944800),
    ORG_NUMBER_OF_EMPLOYEES_SLASH_MEMBERS("org:number_of_employees/members", true, 3, NERTag.ORGANIZATION, Cardinality.SINGLE, new NERTag[]{NUMBER}, new String[]{"CD", "NN"}, 0.0366274831946870950),
    ORG_WEBSITE("org:website", true, 3, NERTag.ORGANIZATION, Cardinality.SINGLE, new NERTag[]{URL}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    // Inverse types
    ORG_EMPLOYEES("org:employees_or_members", false, 68, NERTag.ORGANIZATION, Cardinality.LIST, new NERTag[]{PERSON}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    GPE_EMPLOYEES("gpe:employees_or_members", false, 10, NERTag.GPE, Cardinality.LIST, new NERTag[]{PERSON}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    ORG_STUDENTS("org:students", false, 50, NERTag.ORGANIZATION, Cardinality.LIST, new NERTag[]{PERSON}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    GPE_BIRTHS_IN_CITY("gpe:births_in_city", false, 50, NERTag.GPE, Cardinality.LIST, new NERTag[]{PERSON}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    GPE_BIRTHS_IN_STATE_OR_PROVINCE("gpe:births_in_stateorprovince", false, 50, NERTag.GPE, Cardinality.LIST, new NERTag[]{PERSON}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    GPE_BIRTHS_IN_COUNTRY("gpe:births_in_country", false, 50, NERTag.GPE, Cardinality.LIST, new NERTag[]{PERSON}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    GPE_RESIDENTS_IN_CITY("gpe:residents_of_city", false, 50, NERTag.GPE, Cardinality.LIST, new NERTag[]{PERSON}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    GPE_RESIDENTS_IN_STATE_OR_PROVINCE("gpe:residents_of_stateorprovince", false, 50, NERTag.GPE, Cardinality.LIST, new NERTag[]{PERSON}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    GPE_RESIDENTS_IN_COUNTRY("gpe:residents_of_country", false, 50, NERTag.GPE, Cardinality.LIST, new NERTag[]{PERSON}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    GPE_DEATHS_IN_CITY("gpe:deaths_in_city", false, 50, NERTag.GPE, Cardinality.LIST, new NERTag[]{PERSON}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    GPE_DEATHS_IN_STATE_OR_PROVINCE("gpe:deaths_in_stateorprovince", false, 50, NERTag.GPE, Cardinality.LIST, new NERTag[]{PERSON}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    GPE_DEATHS_IN_COUNTRY("gpe:deaths_in_country", false, 50, NERTag.GPE, Cardinality.LIST, new NERTag[]{PERSON}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    PER_HOLDS_SHARES_IN("per:holds_shares_in", false, 10, NERTag.PERSON, Cardinality.LIST, new NERTag[]{ORGANIZATION}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    GPE_HOLDS_SHARES_IN("gpe:holds_shares_in", false, 10, NERTag.GPE, Cardinality.LIST, new NERTag[]{ORGANIZATION}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    ORG_HOLDS_SHARES_IN("org:holds_shares_in", false, 10, NERTag.ORGANIZATION, Cardinality.LIST, new NERTag[]{ORGANIZATION}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    PER_ORGANIZATIONS_FOUNDED("per:organizations_founded", false, 3, NERTag.PERSON, Cardinality.LIST, new NERTag[]{ORGANIZATION}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    GPE_ORGANIZATIONS_FOUNDED("gpe:organizations_founded", false, 3, NERTag.GPE, Cardinality.LIST, new NERTag[]{ORGANIZATION}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    ORG_ORGANIZATIONS_FOUNDED("org:organizations_founded", false, 3, NERTag.ORGANIZATION, Cardinality.LIST, new NERTag[]{ORGANIZATION}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    PER_TOP_EMPLOYEE_OF("per:top_member_employee_of", false, 5, NERTag.PERSON, Cardinality.LIST, new NERTag[]{ORGANIZATION}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    GPE_MEMBER_OF("gpe:member_of", false, 10, NERTag.GPE, Cardinality.LIST, new NERTag[]{ORGANIZATION}, new String[]{"NNP"}, 0.0396298781687126140),
    GPE_SUBSIDIARIES("gpe:subsidiaries", false, 10, NERTag.GPE, Cardinality.LIST, new NERTag[]{ORGANIZATION}, new String[]{"NNP"}, 0.0396298781687126140),
    GPE_HEADQUARTERS_IN_CITY("gpe:headquarters_in_city", false, 50, NERTag.GPE, Cardinality.LIST, new NERTag[]{ORGANIZATION}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    GPE_HEADQUARTERS_IN_STATE_OR_PROVINCE("gpe:headquarters_in_stateorprovince", false, 50, NERTag.GPE, Cardinality.LIST, new NERTag[]{ORGANIZATION}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    GPE_HEADQUARTERS_IN_COUNTRY("gpe:headquarters_in_country", false, 50, NERTag.GPE, Cardinality.LIST, new NERTag[]{ORGANIZATION}, new String[]{"NNP", "NN"}, 0.0051544006201478640),
    ;

    public enum Cardinality {
      SINGLE,
      LIST
    }

    /**
     * A canonical name for this relation type. This is the official 2010 relation name,
     * that has since changed.
     */
    public final String canonicalName;
    /**
     * If true, realtation was one of the original (non-inverse) KBP relation.
     */
    public final boolean isOriginalRelation;
    /**
     * A guess of the maximum number of results to query for this relation.
     * Only really relevant for cold start.
     */
    public final int queryLimit;
    /**
     * The entity type (left arg type) associated with this relation. That is, either a PERSON or an ORGANIZATION "slot".
     */
    public final NERTag entityType;
    /**
     * The cardinality of this entity. That is, can multiple right arguments participate in this relation (born_in vs. lived_in)
     */
    public final Cardinality cardinality;
    /**
     * Valid named entity labels for the right argument to this relation
     */
    public final Set<NERTag> validNamedEntityLabels;
    /**
     * Valid POS [prefixes] for the right argument to this relation (e.g., can only take nouns, or can only take numbers, etc.)
     */
    public final Set<String> validPOSPrefixes;
    /**
     * The prior for how often this relation occurs in the training data.
     * Note that this prior is not necessarily accurate for the test data.
     */
    public final double priorProbability;


    RelationType(String canonicalName, boolean isOriginalRelation, int queryLimit, NERTag type, Cardinality cardinality, NERTag[] validNamedEntityLabels, String[] validPOSPrefixes,
                 double priorProbability) {
      this.canonicalName          = canonicalName;
      this.isOriginalRelation     = isOriginalRelation;
      this.queryLimit             = queryLimit;
      this.entityType             = type;
      this.cardinality            = cardinality;
      this.validNamedEntityLabels = new HashSet<>(Arrays.asList(validNamedEntityLabels));
      this.validPOSPrefixes       = new HashSet<>(Arrays.asList(validPOSPrefixes));
      this.priorProbability       = priorProbability;
    }

    /** A small cache of names to relation types; we call fromString() a lot in the code, usually expecting it to be very fast */
    private static final Map<String, RelationType> cachedFromString = new HashMap<>();

    /** Find the slot for a given name */
    public static Optional<RelationType> fromString(String name) {
      if (name == null) { return Optional.empty(); }
      String originalName = name;
      if (cachedFromString.get(name) != null) { return Optional.of(cachedFromString.get(name)); }
      if (cachedFromString.containsKey(name)) { return Optional.empty(); }
      // Try naive
      for (RelationType slot : RelationType.values()) {
        if (slot.canonicalName.equals(name) || slot.name().equals(name)) {
          cachedFromString.put(originalName, slot);
          return Optional.of(slot);
        }
      }
      // Replace slashes
      name = name.toLowerCase().replaceAll("[Ss][Ll][Aa][Ss][Hh]", "/");
      for (RelationType slot : RelationType.values()) {
        if (slot.canonicalName.equalsIgnoreCase(name)) {
          cachedFromString.put(originalName, slot);
          return Optional.of(slot);
        }
      }
      cachedFromString.put(originalName, null);
      return Optional.empty();
    }


    /**
     * Returns whether two entity types could plausibly have a relation hold between them.
     * That is, is there a known relation type that would hold between these two entity types.
     * @param entityType The NER tag of the entity.
     * @param slotValueType The NER tag of the slot value.
     * @return True if there is a plausible relation which could occur between these two types.
     */
    public static boolean plausiblyHasRelation(NERTag entityType, NERTag slotValueType) {
      for (RelationType rel : RelationType.values()) {
        if (rel.entityType == entityType && rel.validNamedEntityLabels.contains(slotValueType)) {
          return true;
        }
      }
      return false;
    }
  }


  /** A class to compute the accuracy of a relation extractor. */
  @SuppressWarnings("unused")
  public static class Accuracy {

    private class PerRelationStat implements  Comparable<PerRelationStat> {
      public final String name;
      public final double precision;
      public final double recall;
      public final int predictedCount;
      public final int goldCount;
      public PerRelationStat(String name, double precision, double recall, int predictedCount, int goldCount) {
        this.name = name;
        this.precision = precision;
        this.recall = recall;
        this.predictedCount = predictedCount;
        this.goldCount = goldCount;
      }
      public double f1() {
        if (precision == 0.0 && recall == 0.0) {
          return 0.0;
        } else {
          return 2.0 * precision * recall / (precision + recall);
        }
      }
      @SuppressWarnings("NullableProblems")
      @Override
      public int compareTo(PerRelationStat o) {
        if (this.precision < o.precision) {
          return -1;
        } else if (this.precision > o.precision) {
          return 1;
        } else {
          return 0;
        }
      }
      @Override
      public String toString() {
        DecimalFormat df = new DecimalFormat("0.00%");
        return "[" + name + "]  pred/gold: " + predictedCount + "/" + goldCount + "  P: " + df.format(precision) + "  R: " + df.format(recall) + "  F1: " + df.format(f1());
      }
    }

    private Counter<String> correctCount   = new ClassicCounter<>();
    private Counter<String> predictedCount = new ClassicCounter<>();
    private Counter<String> goldCount      = new ClassicCounter<>();
    private Counter<String> totalCount     = new ClassicCounter<>();
    public final ConfusionMatrix<String> confusion = new ConfusionMatrix<>();


    public void predict(Set<String> predictedRelationsRaw, Set<String> goldRelationsRaw) {
      Set<String> predictedRelations = new HashSet<>(predictedRelationsRaw);
      predictedRelations.remove(NO_RELATION);
      Set<String> goldRelations = new HashSet<>(goldRelationsRaw);
      goldRelations.remove(NO_RELATION);
      // Register the prediction
      for (String pred : predictedRelations) {
        if (goldRelations.contains(pred)) {
          correctCount.incrementCount(pred);
        }
        predictedCount.incrementCount(pred);
      }
      goldRelations.forEach(goldCount::incrementCount);
      HashSet<String> allRelations = new HashSet<String>(){{ addAll(predictedRelations); addAll(goldRelations); }};
      allRelations.forEach(totalCount::incrementCount);

      // Register the confusion matrix
      if (predictedRelations.size() == 1 && goldRelations.size() == 1) {
        confusion.add(predictedRelations.iterator().next(), goldRelations.iterator().next());
      }
      if (predictedRelations.size() == 1 && goldRelations.size() == 0) {
        confusion.add(predictedRelations.iterator().next(), "NR");
      }
      if (predictedRelations.size() == 0 && goldRelations.size() == 1) {
        confusion.add("NR", goldRelations.iterator().next());
      }
    }

    public double accuracy(String relation) {
      return correctCount.getCount(relation) / totalCount.getCount(relation);
    }

    public double accuracyMicro() {
      return correctCount.totalCount() / totalCount.totalCount();
    }

    public double accuracyMacro() {
      double sumAccuracy = 0.0;
      for (String rel : totalCount.keySet()) {
        sumAccuracy += accuracy(rel);
      }
      return sumAccuracy / ((double) totalCount.size());
    }


    public double precision(String relation) {
      if (predictedCount.getCount(relation) == 0) {
        return 1.0;
      }
      return correctCount.getCount(relation) / predictedCount.getCount(relation);
    }

    public double precisionMicro() {
      if (predictedCount.totalCount() == 0) {
        return 1.0;
      }
      return correctCount.totalCount() / predictedCount.totalCount();
    }

    public double precisionMacro() {
      double sumPrecision = 0.0;
      for (String rel : totalCount.keySet()) {
        sumPrecision += precision(rel);
      }
      return sumPrecision / ((double) totalCount.size());
    }


    public double recall(String relation) {
      if (goldCount.getCount(relation) == 0) {
        return 0.0;
      }
      return correctCount.getCount(relation) / goldCount.getCount(relation);
    }

    public double recallMicro() {
      if (goldCount.totalCount() == 0) {
        return 0.0;
      }
      return correctCount.totalCount() / goldCount.totalCount();
    }

    public double recallMacro() {
      double sumRecall = 0.0;
      for (String rel : totalCount.keySet()) {
        sumRecall += recall(rel);
      }
      return sumRecall / ((double) totalCount.size());
    }

    public double f1(String relation) {
      return 2.0 * precision(relation) * recall(relation) / (precision(relation) + recall(relation));
    }

    public double f1Micro() {
      return 2.0 * precisionMicro() * recallMicro() / (precisionMicro() + recallMicro());
    }

    public double f1Macro() {
      return 2.0 * precisionMacro() * recallMacro() / (precisionMacro() + recallMacro());
    }

    public void dumpPerRelationStats(PrintStream out) {
      List<PerRelationStat> stats = goldCount.keySet().stream().map(relation -> new PerRelationStat(relation, precision(relation), recall(relation), (int) predictedCount.getCount(relation), (int) goldCount.getCount(relation))).collect(Collectors.toList());
      Collections.sort(stats);
      out.println("Per-relation Accuracy");
      for (PerRelationStat stat : stats) {
        out.println(stat.toString());
      }
    }

    public void dumpPerRelationStats() {
      dumpPerRelationStats(System.out);

    }

    public void toString(PrintStream out) {
      out.println();
      out.println("ACCURACY  (micro average): " + new DecimalFormat("0.000%").format(accuracyMicro()));
      out.println("PRECISION (micro average): " + new DecimalFormat("0.000%").format(precisionMicro()));
      out.println("RECALL    (micro average): " + new DecimalFormat("0.000%").format(recallMicro()));
      out.println("F1        (micro average): " + new DecimalFormat("0.000%").format(f1Micro()));
      out.println();
      out.println("ACCURACY  (macro average): " + new DecimalFormat("0.000%").format(accuracyMacro()));
      out.println("PRECISION (macro average): " + new DecimalFormat("0.000%").format(precisionMacro()));
      out.println("RECALL    (macro average): " + new DecimalFormat("0.000%").format(recallMacro()));
      out.println("F1        (macro average): " + new DecimalFormat("0.000%").format(f1Macro()));
      out.println();
    }

    public String toString() {
      ByteArrayOutputStream bs = new ByteArrayOutputStream();
      PrintStream out = new PrintStream(bs);
      toString(out);
      return bs.toString();
    }

  }

  /**
   * A list of triggers for top employees.
   */
  private static final Set<String> TOP_EMPLOYEE_TRIGGERS = Collections.unmodifiableSet(new HashSet<String>(){{
    add("executive");
    add("chairman");
    add("president");
    add("chief");
    add("head");
    add("general");
    add("ceo");
    add("officer");
    add("founder");
    add("found");
    add("leader");
    add("vice");
    add("king");
    add("prince");
    add("manager");
    add("host");
    add("minister");
    add("adviser");
    add("boss");
    add("chair");
    add("ambassador");
    add("shareholder");
    add("star");
    add("governor");
    add("investor");
    add("representative");
    add("dean");
    add("commissioner");
    add("deputy");
    add("commander");
    add("scientist");
    add("midfielder");
    add("speaker");
    add("researcher");
    add("editor");
    add("chancellor");
    add("fellow");
    add("leadership");
    add("diplomat");
    add("attorney");
    add("associate");
    add("striker");
    add("pilot");
    add("captain");
    add("banker");
    add("mayer");
    add("premier");
    add("producer");
    add("architect");
    add("designer");
    add("major");
    add("advisor");
    add("presidency");
    add("senator");
    add("specialist");
    add("faculty");
    add("monitor");
    add("chairwoman");
    add("mayor");
    add("columnist");
    add("mediator");
    add("prosecutor");
    add("entrepreneur");
    add("creator");
    add("superstar");
    add("commentator");
    add("principal");
    add("operative");
    add("businessman");
    add("peacekeeper");
    add("investigator");
    add("coordinator");
    add("knight");
    add("lawmaker");
    add("justice");
    add("publisher");
    add("playmaker");
    add("moderator");
    add("negotiator");
  }});


  /**
   * <p>
   *   Often, features fall naturally into <i>feature templates</i> and their associated value.
   *   For example, unigram features have a feature template of unigram, and a feature value of the word
   *   in question.
   * </p>
   *
   * <p>
   *   This method is a convenience convention for defining these feature template / value pairs.
   *   The advantage of using the method is that it allows for easily finding the feature template for a
   *   given feature value; thus, you can do feature selection post-hoc on the String features by splitting
   *   out certain feature templates.
   * </p>
   *
   * <p>
   *   Note that spaces in the feature value are also replaced with a special character, mostly out of
   *   paranoia.
   * </p>
   *
   * @param features The feature counter we are updating.
   * @param featureTemplate The feature template to add a value to.
   * @param featureValue The value of the feature template. This is joined with the template, so it
   *                     need only be unique within the template.
   */
  private static void indicator(Counter<String> features, String featureTemplate, String featureValue) {
    features.incrementCount(featureTemplate + "ℵ" + featureValue.replace(' ', 'ˑ'));
  }

  /**
   * Get information from the span between the two mentions.
   * Canonically, get the words in this span.
   * For instance, for "Obama was born in Hawaii", this would return a list
   * "was born in" if the selector is <code>CoreLabel::token</code>;
   * or "be bear in" if the selector is <code>CoreLabel::lemma</code>.
   *
   * @param input The featurizer input.
   * @param selector The field to compute for each element in the span. A good default is <code></code>CoreLabel::word</code> or <code></code>CoreLabel::token</code>
   * @param <E> The type of element returned by the selector.
   *
   * @return A list of elements between the two mentions.
   */
  @SuppressWarnings("unchecked")
  private  static <E> List<E> spanBetweenMentions(FeaturizerInput input, Function<CoreLabel, E> selector) {
    List<CoreLabel> sentence = input.sentence.asCoreLabels(Sentence::nerTags);
    Span subjSpan = input.subjectSpan;
    Span objSpan = input.objectSpan;

    // Corner cases
    if (Span.overlaps(subjSpan, objSpan)) {
      return Collections.EMPTY_LIST;
    }

    // Get the range between the subject and object
    int begin = subjSpan.end();
    int end = objSpan.start();
    if (begin > end) {
      begin = objSpan.end();
      end = subjSpan.start();
    }
    if (begin > end) {
      throw new IllegalArgumentException("Gabor sucks at logic and he should feel bad about it: " + subjSpan + " and " + objSpan);
    } else if (begin == end) {
      return Collections.EMPTY_LIST;
    }

    // Compute the return value
    List<E> rtn = new ArrayList<>();
    for (int i = begin; i < end; ++i) {
      rtn.add(selector.apply(sentence.get(i)));
    }
    return rtn;
  }

  /**
   * <p>
   *   Span features often only make sense if the subject and object are positioned at the correct ends of the span.
   *   For example, "x is the son of y" and "y is the son of x" have the same span feature, but mean different things
   *   depending on where x and y are.
   * </p>
   *
   * <p>
   *   This is a simple helper to position a dummy subject and object token appropriately.
   * </p>
   *
   * @param input The featurizer input.
   * @param feature The span feature to augment.
   *
   * @return The augmented feature.
   */
  private static String withMentionsPositioned(FeaturizerInput input, String feature) {
    if (input.subjectSpan.isBefore(input.objectSpan)) {
      return "+__SUBJ__ " + feature + " __OBJ__";
    } else {
      return "__OBJ__ " + feature + " __SUBJ__";
    }
  }

  @SuppressWarnings("UnusedParameters")
  private static void denseFeatures(FeaturizerInput input, Document doc, Sentence sentence, ClassicCounter<String> feats) {
    boolean subjBeforeObj = input.subjectSpan.isBefore(input.objectSpan);

    // Type signature
    indicator(feats, "type_signature", input.subjectType + "," + input.objectType);

    // Relative position
    indicator(feats, "subj_before_obj", subjBeforeObj ? "y" : "n");
  }

  @SuppressWarnings("UnusedParameters")
  private static void surfaceFeatures(FeaturizerInput input, Document doc, Sentence simpleSentence, ClassicCounter<String> feats) {
    List<String> lemmaSpan = spanBetweenMentions(input, CoreLabel::lemma);
    List<String> nerSpan = spanBetweenMentions(input, CoreLabel::ner);
    List<String> posSpan = spanBetweenMentions(input, CoreLabel::tag);

    // Unigram features of the sentence
    List<CoreLabel> tokens = input.sentence.asCoreLabels(Sentence::nerTags);
    for (CoreLabel token : tokens) {
      indicator(feats, "sentence_unigram", token.lemma());
    }

    // Full lemma span ( -0.3 F1 )
//    if (lemmaSpan.size() <= 5) {
//      indicator(feats, "full_lemma_span", withMentionsPositioned(input, StringUtils.join(lemmaSpan, " ")));
//    }

    // Lemma n-grams
    String lastLemma = "_^_";
    for (String lemma : lemmaSpan) {
      indicator(feats, "lemma_bigram", withMentionsPositioned(input, lastLemma + " " + lemma));
      indicator(feats, "lemma_unigram", withMentionsPositioned(input, lemma));
      lastLemma = lemma;
    }
    indicator(feats, "lemma_bigram", withMentionsPositioned(input, lastLemma + " _$_"));

    // NER + lemma bi-grams
    for (int i = 0; i < lemmaSpan.size() - 1; ++i) {
      if (!"O".equals(nerSpan.get(i)) && "O".equals(nerSpan.get(i + 1)) && "IN".equals(posSpan.get(i + 1))) {
        indicator(feats, "ner/lemma_bigram", withMentionsPositioned(input, nerSpan.get(i) + " " + lemmaSpan.get(i + 1)));
      }
      if (!"O".equals(nerSpan.get(i + 1)) && "O".equals(nerSpan.get(i)) && "IN".equals(posSpan.get(i))) {
        indicator(feats, "ner/lemma_bigram", withMentionsPositioned(input, lemmaSpan.get(i) + " " + nerSpan.get(i + 1)));
      }
    }

    // Distance between mentions
    String distanceBucket = ">10";
    if (lemmaSpan.size() == 0) {
      distanceBucket = "0";
    } else if (lemmaSpan.size() <= 3) {
      distanceBucket = "<=3";
    } else if (lemmaSpan.size() <= 5) {
      distanceBucket = "<=5";
    } else if (lemmaSpan.size() <= 10) {
      distanceBucket = "<=10";
    } else if (lemmaSpan.size() <= 15) {
      distanceBucket = "<=15";
    }
    indicator(feats, "distance_between_entities_bucket", distanceBucket);

    // Punctuation features
    int numCommasInSpan = 0;
    int numQuotesInSpan = 0;
    int parenParity = 0;
    for (String lemma : lemmaSpan) {
      if (lemma.equals(",")) { numCommasInSpan += 1; }
      if (lemma.equals("\"") || lemma.equals("``") || lemma.equals("''")) {
        numQuotesInSpan += 1;
      }
      if (lemma.equals("(") || lemma.equals("-LRB-")) { parenParity += 1; }
      if (lemma.equals(")") || lemma.equals("-RRB-")) { parenParity -= 1; }
    }
    indicator(feats, "comma_parity", numCommasInSpan % 2 == 0 ? "even" : "odd");
    indicator(feats, "quote_parity", numQuotesInSpan % 2 == 0 ? "even" : "odd");
    indicator(feats, "paren_parity", "" + parenParity);

    // Is broken by entity
    Set<String> intercedingNERTags = nerSpan.stream().filter(ner -> !ner.equals("O")).collect(Collectors.toSet());
    if (!intercedingNERTags.isEmpty()) {
      indicator(feats, "has_interceding_ner", "t");
    }
    for (String ner : intercedingNERTags) {
      indicator(feats, "interceding_ner", ner);
    }

    // Left and right context
    List<CoreLabel> sentence = input.sentence.asCoreLabels(Sentence::nerTags);
    if (input.subjectSpan.start() == 0) {
      indicator(feats, "subj_left", "^");
    } else {
      indicator(feats, "subj_left", sentence.get(input.subjectSpan.start() - 1).lemma());
    }
    if (input.subjectSpan.end() == sentence.size()) {
      indicator(feats, "subj_right", "$");
    } else {
      indicator(feats, "subj_right", sentence.get(input.subjectSpan.end()).lemma());
    }
    if (input.objectSpan.start() == 0) {
      indicator(feats, "obj_left", "^");
    } else {
      indicator(feats, "obj_left", sentence.get(input.objectSpan.start() - 1).lemma());
    }
    if (input.objectSpan.end() == sentence.size()) {
      indicator(feats, "obj_right", "$");
    } else {
      indicator(feats, "obj_right", sentence.get(input.objectSpan.end()).lemma());
    }

    // Skip-word patterns
    if (lemmaSpan.size() == 1 && input.subjectSpan.isBefore(input.objectSpan)) {
      String left = input.subjectSpan.start() == 0 ? "^" : sentence.get(input.subjectSpan.start() - 1).lemma();
      indicator(feats, "X<subj>Y<obj>", left + "_" + lemmaSpan.get(0));
    }
  }


  private static void dependencyFeatures(FeaturizerInput input, Document doc, Sentence sentence, ClassicCounter<String> feats) {
    int subjectHead = sentence.algorithms().headOfSpan(input.subjectSpan);
    int objectHead = sentence.algorithms().headOfSpan(input.objectSpan);

//    indicator(feats, "subject_head", sentence.lemma(subjectHead));
//    indicator(feats, "object_head", sentence.lemma(objectHead));
    if (input.objectType.isRegexNERType) {
      indicator(feats, "object_head", sentence.lemma(objectHead));
    }

    // Get the dependency path
    List<String> depparsePath = doc.sentence(0).algorithms().dependencyPathBetween(subjectHead, objectHead, Sentence::lemmas);

    // Chop out appos edges
    if (depparsePath.size() > 3) {
      List<Integer> apposChunks = new ArrayList<>();
      for (int i = 1; i < depparsePath.size() - 1; ++i) {
        if ("-appos->".equals(depparsePath.get(i))) {
          if (i != 1) {
            apposChunks.add(i - 1);
          }
          apposChunks.add(i);
        } else if ("<-appos-".equals(depparsePath.get(i))) {
          if (i < depparsePath.size() - 1) {
            apposChunks.add(i + 1);
          }
          apposChunks.add(i);
        }
      }
      Collections.sort(apposChunks);
      for (int i = apposChunks.size() - 1; i >= 0; --i) {
        depparsePath.remove(i);
      }
    }

    // Dependency path distance buckets
    String distanceBucket = ">10";
    if (depparsePath.size() == 3) {
      distanceBucket = "<=3";
    } else if (depparsePath.size() <= 5) {
      distanceBucket = "<=5";
    } else if (depparsePath.size() <= 7) {
      distanceBucket = "<=7";
    } else if (depparsePath.size() <= 9) {
      distanceBucket = "<=9";
    } else if (depparsePath.size() <= 13) {
      distanceBucket = "<=13";
    } else if (depparsePath.size() <= 17) {
      distanceBucket = "<=17";
    }
    indicator(feats, "parse_distance_between_entities_bucket", distanceBucket);

    // Add the path features
    if (depparsePath.size() > 2 && depparsePath.size() <= 7) {
//      indicator(feats, "deppath", StringUtils.join(depparsePath.subList(1, depparsePath.size() - 1), ""));
//      indicator(feats, "deppath_unlex", StringUtils.join(depparsePath.subList(1, depparsePath.size() - 1).stream().filter(x -> x.startsWith("-") || x.startsWith("<")), ""));
      indicator(feats, "deppath_w/tag",
          sentence.posTag(subjectHead) + StringUtils.join(depparsePath.subList(1, depparsePath.size() - 1), "") + sentence.posTag(objectHead));
      indicator(feats, "deppath_w/ner",
          input.subjectType + StringUtils.join(depparsePath.subList(1, depparsePath.size() - 1), "") + input.objectType);
    }

    // Add the edge features
    //noinspection Convert2streamapi
    for (String node : depparsePath) {
      if (!node.startsWith("-") && !node.startsWith("<-")) {
        indicator(feats, "deppath_word", node);
      }
    }
    for (int i = 0; i < depparsePath.size() - 1; ++i) {
      indicator(feats, "deppath_edge", depparsePath.get(i) + depparsePath.get(i + 1));
    }
    for (int i = 0; i < depparsePath.size() - 2; ++i) {
      indicator(feats, "deppath_chunk", depparsePath.get(i) + depparsePath.get(i + 1) + depparsePath.get(i + 2));
    }
  }


  @SuppressWarnings("UnusedParameters")
  private static void relationSpecificFeatures(FeaturizerInput input, Document doc, Sentence sentence, ClassicCounter<String> feats) {
    if (input.objectType.equals(NERTag.NUMBER)) {
      // Bucket the object value if it is a number
      // This is to prevent things like "age:9000" and to soft penalize "age:one"
      // The following features are extracted:
      //   1. Whether the object parses as a number (should always be true)
      //   2. Whether the object is an integer
      //   3. If the object is an integer, around what value is it (bucketed around common age values)
      //   4. Was the number spelled out, or written as a numeric number
      try {
        Number number = NumberNormalizer.wordToNumber(input.getObjectText());
        if (number != null) {
          indicator(feats, "obj_parsed_as_num", "t");
          if (number.equals(number.intValue())) {
            indicator(feats, "obj_isint", "t");
            int numAsInt = number.intValue();
            String bucket = "<0";
            if (numAsInt == 0) {
              bucket = "0";
            } else if (numAsInt == 1) {
              bucket = "1";
            } else if (numAsInt < 5) {
              bucket = "<5";
            } else if (numAsInt < 18) {
              bucket = "<18";
            } else if (numAsInt < 25) {
              bucket = "<25";
            } else if (numAsInt < 50) {
              bucket = "<50";
            } else if (numAsInt < 80) {
              bucket = "<80";
            } else if (numAsInt < 125) {
              bucket = "<125";
            } else if (numAsInt >= 100) {
              bucket = ">125";
            }
            indicator(feats, "obj_number_bucket", bucket);
          } else {
            indicator(feats, "obj_isint", "f");
          }
          if (input.getObjectText().replace(",", "").equalsIgnoreCase(number.toString())) {
            indicator(feats, "obj_spelledout_num", "f");
          } else {
            indicator(feats, "obj_spelledout_num", "t");
          }
        } else {
          indicator(feats, "obj_parsed_as_num", "f");
        }
      } catch (NumberFormatException e) {
        indicator(feats, "obj_parsed_as_num", "f");
      }
      // Special case dashes and the String "one"
      if (input.getObjectText().contains("-")) {
        indicator(feats, "obj_num_has_dash", "t");
      } else {
        indicator(feats, "obj_num_has_dash", "f");
      }
      if (input.getObjectText().equalsIgnoreCase("one")) {
        indicator(feats, "obj_num_is_one", "t");
      } else {
        indicator(feats, "obj_num_is_one", "f");
      }
    }

    if (
        (input.subjectType == NERTag.PERSON && input.objectType.equals(NERTag.ORGANIZATION)) ||
            (input.subjectType == NERTag.ORGANIZATION && input.objectType.equals(NERTag.PERSON))
        ) {
      // Try to capture some denser features for employee_of
      // These are:
      //   1. Whether a TITLE tag occurs either before, after, or inside the relation span
      //   2. Whether a top employee trigger occurs either before, after, or inside the relation span
      Span relationSpan = Span.union(input.subjectSpan, input.objectSpan);
      // (triggers before span)
      for (int i = Math.max(0, relationSpan.start() - 5); i < relationSpan.start(); ++i) {
        if ("TITLE".equals(sentence.nerTag(i))) {
          indicator(feats, "title_before", "t");
        }
        if (TOP_EMPLOYEE_TRIGGERS.contains(sentence.word(i).toLowerCase())) {
          indicator(feats, "top_employee_trigger_before", "t");
        }
      }
      // (triggers after span)
      for (int i = relationSpan.end(); i < Math.min(sentence.length(), relationSpan.end()); ++i) {
        if ("TITLE".equals(sentence.nerTag(i))) {
          indicator(feats, "title_after", "t");
        }
        if (TOP_EMPLOYEE_TRIGGERS.contains(sentence.word(i).toLowerCase())) {
          indicator(feats, "top_employee_trigger_after", "t");
        }
      }
      // (triggers inside span)
      for (int i : relationSpan) {
        if ("TITLE".equals(sentence.nerTag(i))) {
          indicator(feats, "title_inside", "t");
        }
        if (TOP_EMPLOYEE_TRIGGERS.contains(sentence.word(i).toLowerCase())) {
          indicator(feats, "top_employee_trigger_inside", "t");
        }
      }
    }
  }

  public static Counter<String> features(FeaturizerInput input) {
    // Serialize the document to a protocol buffer.
    // This is faster than calling the ProtobufAnnotationSerializer methods implicitly called
    // in the Simple CoreNLP constructors, since we know exactly what should go into these protos.
    // This whole block is just an efficiency improvement.
    CoreMap sentenceMap = input.sentence.asCoreMap(Sentence::nerTags, Sentence::lemmas, Sentence::dependencyGraph);
    List<CoreLabel> tokens = sentenceMap.get(CoreAnnotations.TokensAnnotation.class);
    List<CoreNLPProtos.Token> tokensProto = new ArrayList<>();
    for (CoreLabel token : tokens) {
      CoreNLPProtos.Token tokenProto = CoreNLPProtos.Token.newBuilder()
          .setWord(token.word())
          .setLemma(token.lemma())
          .setPos(token.tag())
          .setNer(token.ner())
          .setBeginChar(token.beginPosition())
          .setEndChar(token.endPosition())
          .setBeginIndex(token.index())
          .setEndIndex(token.index() + 1)
          .build();
      tokensProto.add(tokenProto);
    }
    CoreNLPProtos.DependencyGraph tree = ProtobufAnnotationSerializer.toProto(sentenceMap.get(SemanticGraphCoreAnnotations.BasicDependenciesAnnotation.class));
    CoreNLPProtos.Sentence sentenceProto = CoreNLPProtos.Sentence.newBuilder()
        .addAllToken(tokensProto)
        .setBasicDependencies(tree)
        .setCollapsedDependencies(tree)
        .setCollapsedCCProcessedDependencies(tree)
        .setText(sentenceMap.get(CoreAnnotations.TextAnnotation.class))
        .setSentenceIndex(sentenceMap.get(CoreAnnotations.SentenceIndexAnnotation.class))
        .setTokenOffsetBegin(0)
        .setTokenOffsetEnd(tokens.size())
        .build();
    CoreNLPProtos.Document docProto = CoreNLPProtos.Document.newBuilder()
        .addAllSentence(Collections.singletonList(sentenceProto))
        .setText(sentenceMap.get(CoreAnnotations.TextAnnotation.class))
        .build();

    // Get useful variables
    ClassicCounter<String> feats = new ClassicCounter<>();
    if (Span.overlaps(input.subjectSpan, input.objectSpan) || input.subjectSpan.size() == 0 || input.objectSpan.size() == 0) {
      return new ClassicCounter<>();
    }
    Document doc = new Document(docProto);
    Sentence sentence = doc.sentence(0);

    // Actually featurize
    denseFeatures(input, doc, sentence, feats);
    surfaceFeatures(input, doc, sentence, feats);
    dependencyFeatures(input, doc, sentence, feats);
    relationSpecificFeatures(input, doc, sentence, feats);

    return feats;
  }

  /**
   * Read a dataset from a CoNLL formatted input file
   * @param conllInputFile The input file, formatted as a TSV
   * @return A list of examples.
   */
  @SuppressWarnings("StatementWithEmptyBody")
  public static List<Pair<FeaturizerInput, String>> readDataset(File conllInputFile) throws IOException {
    BufferedReader reader = IOUtils.readerFromFile(conllInputFile);
    List<Pair<FeaturizerInput, String>> examples = new ArrayList<>();

    int i = 0;
    String relation = null;
    List<String> tokens = new ArrayList<>();
    Span subject = new Span(Integer.MAX_VALUE, Integer.MIN_VALUE);
    NERTag subjectNER = null;
    Span object = new Span(Integer.MAX_VALUE, Integer.MIN_VALUE);
    NERTag objectNER = null;

    String line;
    while ( (line = reader.readLine()) != null ) {
      if (line.startsWith("#")) {
        continue;
      }
      line = line.replace("\\#", "#");
      String[] fields = line.split("\t");
      if (relation == null) {
        // Case: read the relation
        assert fields.length == 1;
        relation = fields[0];
      } else if (fields.length == 3) {
        // Case: read a token
        tokens.add(fields[0]);
        if ("SUBJECT".equals(fields[1])) {
          subject = new Span(Math.min(subject.start(), i), Math.max(subject.end(), i + 1));
          subjectNER = NERTag.valueOf(fields[2].toUpperCase());
        } else if ("OBJECT".equals(fields[1])) {
          object = new Span(Math.min(object.start(), i), Math.max(object.end(), i + 1));
          objectNER = NERTag.valueOf(fields[2].toUpperCase());
        } else if ("-".equals(fields[1])) {
          // do nothing
        } else {
          throw new IllegalStateException("Could not parse CoNLL file");
        }
        i += 1;
      } else if ("".equals(line.trim())) {
        // Case: commit a sentence
        examples.add(Pair.makePair(new FeaturizerInput(
            subject,
            object,
            subjectNER,
            objectNER,
            new Sentence(tokens)
        ), relation));

        // (clear the variables)
        i = 0;
        relation = null;
        tokens = new ArrayList<>();
        subject = new Span(Integer.MAX_VALUE, Integer.MIN_VALUE);
        object = new Span(Integer.MAX_VALUE, Integer.MIN_VALUE);
      } else {
        throw new IllegalStateException("Could not parse CoNLL file");
      }
    }

    return examples;
  }


  /**
   * Create a classifier factory
   * @param <L> The label class of the factory
   * @return A factory to minimize a classifier against.
   */
  private static <L> LinearClassifierFactory<L, String> initFactory(double sigma) {
    LinearClassifierFactory<L,String> factory = new LinearClassifierFactory<>();
    Factory<Minimizer<DiffFunction>> minimizerFactory;
    switch(minimizer) {
      case QN:
        minimizerFactory = () -> new QNMinimizer(15);
        break;
      case SGD:
        minimizerFactory = () -> new SGDMinimizer<>(sigma, 100, 1000);
        break;
      case HYBRID:
        factory.useHybridMinimizerWithInPlaceSGD(100, 1000, sigma);
        minimizerFactory = () -> {
          SGDMinimizer<DiffFunction> firstMinimizer = new SGDMinimizer<>(sigma, 50, 1000);
          QNMinimizer secondMinimizer = new QNMinimizer(15);
          return new HybridMinimizer(firstMinimizer, secondMinimizer, 50);
        };
        break;
      case L1:
        minimizerFactory = () -> {
          try {
            return MetaClass.create("edu.stanford.nlp.optimization.OWLQNMinimizer").createInstance(sigma);
          } catch (Exception e) {
            log.err("Could not create l1 minimizer! Reverting to l2.");
            return new QNMinimizer(15);
          }
        };
        break;
      default:
        throw new IllegalStateException("Unknown minimizer: " + minimizer);
    }
    factory.setMinimizerCreator(minimizerFactory);
    return factory;
  }


  /**
   * Train a multinomial classifier off of the provided dataset.
   * @param dataset The dataset to train the classifier off of.
   * @return A classifier.
   */
  public static Classifier<String, String> trainMultinomialClassifier(
      GeneralDataset<String, String> dataset,
      int featureThreshold,
      double sigma) {
    // Set up the dataset and factory
    log.info("Applying feature threshold (" + featureThreshold + ")...");
    dataset.applyFeatureCountThreshold(featureThreshold);
    log.info("Randomizing dataset...");
    dataset.randomize(42l);
    log.info("Creating factory...");
    LinearClassifierFactory<String,String> factory = initFactory(sigma);

    // Train the final classifier
    log.info("BEGIN training");
    LinearClassifier<String, String> classifier = factory.trainClassifier(dataset);
    log.info("END training");

    // Debug
    Accuracy trainAccuracy = new Accuracy();
    for (Datum<String, String> datum : dataset) {
      String guess = classifier.classOf(datum);
      trainAccuracy.predict(Collections.singleton(guess), Collections.singleton(datum.label()));
    }
    log.info("Training accuracy:");
    log.info(trainAccuracy.toString());
    log.info("");

    // Return the classifier
    return classifier;
  }


  /**
   * The implementing classifier of this extractor.
   */
  private final Classifier<String, String> classifier;

  /**
   * Create a new KBP relation extractor, from the given implementing classifier.
   * @param classifier The implementing classifier.
   */
  public KBPRelationExtractor(Classifier<String, String> classifier) {
    this.classifier = classifier;
  }


  /**
   * Score the given input, returning both the classification decision and the
   * probability of that decision.
   * Note that this method will not return a relation which does not type check.
   *
   *
   * @param input The input to classify.
   * @return A pair with the relation we classified into, along with its confidence.
   */
  public Pair<String,Double> classify(FeaturizerInput input) {
    RVFDatum<String, String> datum = new RVFDatum<>(features(input));
    Counter<String> scores =  classifier.scoresOf(datum);
    Counters.expInPlace(scores);
    Counters.normalize(scores);
    String best = Counters.argmax(scores);
    // While it doesn't type check, continue going down the list.
    // NO_RELATION is always an option somewhere in there, so safe to keep going...
    while (!NO_RELATION.equals(best) && !RelationType.fromString(best).get().validNamedEntityLabels.contains(input.objectType)) {
      scores.remove(best);
      Counters.normalize(scores);
      best = Counters.argmax(scores);
    }
    return Pair.makePair(best, scores.getCount(best));
  }


  public static void main(String[] args) throws IOException {
    RedwoodConfiguration.standard().apply();  // Disable SLF4J crap.
    ArgumentParser.fillOptions(KBPRelationExtractor.class, args);  // Fill command-line options

    // Load the data
    forceTrack("Loading training data");
    forceTrack("Training data");
    List<Pair<FeaturizerInput, String>> trainExamples = readDataset(TRAIN_FILE);
    log.info("Read " + trainExamples.size() + " examples");
    log.info("" + trainExamples.stream().map(Pair::second).filter(NO_RELATION::equals).count() + " are " + NO_RELATION);
    endTrack("Training data");
    forceTrack("Test data");
    List<Pair<FeaturizerInput, String>> testExamples = readDataset(TEST_FILE);
    log.info("Read " + testExamples.size() + " examples");
    endTrack("Test data");
    endTrack("Loading training data");

    // Featurize + create the dataset
    forceTrack("Creating dataset");
    RVFDataset<String, String> dataset = new RVFDataset<>();
    final AtomicInteger i = new AtomicInteger(0);
    long beginTime = System.currentTimeMillis();
    trainExamples.stream().parallel().forEach(example -> {
      if (i.incrementAndGet() % 1000 == 0) {
        log.info("[" + Redwood.formatTimeDifference(System.currentTimeMillis() - beginTime) +
            "] Featurized " + i.get() + " / " + trainExamples.size() + " examples");
      }
      Counter<String> features = features(example.first);  // This takes a while per example
      synchronized (dataset) {
        dataset.add(new RVFDatum<>(features, example.second));
      }
    });
    trainExamples.clear();  // Free up some memory
    endTrack("Creating dataset");

    // Train the classifier
    log.info("Training classifier:");
    Classifier<String, String> classifier = trainMultinomialClassifier(dataset, FEATURE_THRESHOLD, SIGMA);
    dataset.clear();  // Free up some memory

    // Save the classifier
    IOUtils.writeObjectToFile(new KBPRelationExtractor(classifier), MODEL_FILE);

    // Evaluate the classifier
    forceTrack("Test accuracy");
    Accuracy accuracy = new Accuracy();
    forceTrack("Featurizing");
    testExamples.stream().parallel().forEach( example -> {
      RVFDatum<String, String> datum = new RVFDatum<>(features(example.first));
      String predicted = classifier.classOf(datum);
      synchronized (accuracy) {
        accuracy.predict(Collections.singleton(predicted), Collections.singleton(example.second));
      }
    });
    endTrack("Featurizing");
    log(accuracy.toString());
    endTrack("Test accuracy");
  }

}
