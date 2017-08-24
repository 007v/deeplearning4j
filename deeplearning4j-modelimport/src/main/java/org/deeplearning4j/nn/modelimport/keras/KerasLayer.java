/*-
 *
 *  * Copyright 2016 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */

package org.deeplearning4j.nn.modelimport.keras;

import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.nn.conf.ConvolutionMode;
import org.deeplearning4j.nn.conf.InputPreProcessor;
import org.deeplearning4j.nn.conf.graph.GraphVertex;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.Layer;
import org.deeplearning4j.nn.conf.layers.PoolingType;
import org.deeplearning4j.nn.modelimport.keras.config.KerasLayerConfiguration;
import org.deeplearning4j.nn.modelimport.keras.config.KerasLayerConfigurationFactory;
import org.deeplearning4j.nn.modelimport.keras.exceptions.InvalidKerasConfigurationException;
import org.deeplearning4j.nn.modelimport.keras.exceptions.UnsupportedKerasConfigurationException;
import org.deeplearning4j.nn.modelimport.keras.utils.KerasLayerUtils;
import org.deeplearning4j.nn.weights.WeightInit;
import org.nd4j.linalg.activations.IActivation;
import org.nd4j.linalg.activations.impl.*;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.nd4j.linalg.util.ArrayUtil;

import java.util.*;

/**
 * Build Layer from Keras layer configuration.
 *
 * @author dave@skymind.io
 */
@Slf4j
public class KerasLayer {

    public static final String LAYER_FIELD_KERAS_VERSION = "keras_version";
    public static final Map<String, Class<? extends KerasLayer>> customLayers = new HashMap<>();

    public enum DimOrder {NONE, THEANO, TENSORFLOW;}

    protected String className; // Keras layer class name
    protected String layerName; // Keras layer name
    protected int[] inputShape; // Keras layer input shape
    protected DimOrder dimOrder; // Keras layer backend dimension order
    protected List<String> inboundLayerNames; // List of inbound layers
    protected Layer layer; // Resulting DL4J layer
    protected GraphVertex vertex; // Resulting DL4J vertex
    protected Map<String, INDArray> weights; // Weights
    protected double weightL1Regularization = 0.0; // L1 regularization
    protected double weightL2Regularization = 0.0; // L2 regularization
    protected double dropout = 1.0; // Dropout
    protected Integer kerasMajorVersion = 2; // Set 2 as default for now
    protected KerasLayerConfiguration conf;

    /**
     * Constructor with Keras version only.
     *
     * @param kerasVersion major Keras version (1 or 2)
     * @throws UnsupportedKerasConfigurationException
     */
    protected KerasLayer(Integer kerasVersion) throws UnsupportedKerasConfigurationException {
        this.className = null;
        this.layerName = null;
        this.inputShape = null;
        this.dimOrder = DimOrder.NONE;
        this.inboundLayerNames = new ArrayList<String>();
        this.layer = null;
        this.vertex = null;
        this.weights = null;
        this.kerasMajorVersion = kerasVersion;
        this.conf = KerasLayerConfigurationFactory.get(this.kerasMajorVersion);
    }

    /**
     * Default constructor.
     *
     * @throws UnsupportedKerasConfigurationException
     */
    protected KerasLayer() throws UnsupportedKerasConfigurationException {
        this.className = null;
        this.layerName = null;
        this.inputShape = null;
        this.dimOrder = DimOrder.NONE;
        this.inboundLayerNames = new ArrayList<String>();
        this.layer = null;
        this.vertex = null;
        this.weights = null;
        this.conf = KerasLayerConfigurationFactory.get(this.kerasMajorVersion);

    }

    /**
     * Constructor.
     *
     * @param layerConfig dictionary containing Keras layer configuration
     */
    protected KerasLayer(Map<String, Object> layerConfig)
            throws InvalidKerasConfigurationException, UnsupportedKerasConfigurationException {
        this(layerConfig, true);
    }

    /**
     * Constructor. "enforceTrainingConfig" parameter controls whether layer is built for
     * training. This controls behavior of certain exceptions. In training mode, passing
     * an unsupported regularizer will generate an error. In non-training mode, it
     * generates only a warning.
     *
     * @param layerConfig           dictionary containing Keras layer configuration
     * @param enforceTrainingConfig whether layer should be built for training (controls certain exceptions)
     */
    protected KerasLayer(Map<String, Object> layerConfig, boolean enforceTrainingConfig)
            throws InvalidKerasConfigurationException, UnsupportedKerasConfigurationException {
        this.kerasMajorVersion = (Integer) layerConfig.get(LAYER_FIELD_KERAS_VERSION);
        this.conf = KerasLayerConfigurationFactory.get(this.kerasMajorVersion);
        this.className = KerasLayerUtils.getClassNameFromConfig(layerConfig, conf);
        if (this.className == null)
            throw new InvalidKerasConfigurationException("Keras layer class name is missing");
        this.layerName = KerasLayerUtils.getLayerNameFromConfig(layerConfig, conf);
        if (this.layerName == null)
            throw new InvalidKerasConfigurationException("Keras layer class name is missing");
        this.inputShape = KerasLayerUtils.getInputShapeFromConfig(layerConfig, conf);
        this.dimOrder = KerasLayerUtils.getDimOrderFromConfig(layerConfig, conf);
        this.inboundLayerNames = KerasLayerUtils.getInboundLayerNamesFromConfig(layerConfig, conf);
        this.layer = null;
        this.vertex = null;
        this.weights = null;
        this.weightL1Regularization = getWeightL1RegularizationFromConfig(layerConfig, enforceTrainingConfig);
        this.weightL2Regularization = getWeightL2RegularizationFromConfig(layerConfig, enforceTrainingConfig);
        this.dropout = KerasLayerUtils.getDropoutFromConfig(layerConfig, conf);
        KerasLayerUtils.checkForUnsupportedConfigurations(layerConfig, enforceTrainingConfig, conf);
    }

    /**
     * Register a custom layer
     * @param layerName name of custom layer
     * @param configClass class of custom layer
     */
    public static void registerCustomLayer(String layerName, Class<? extends KerasLayer> configClass) {
        customLayers.put(layerName, configClass);
    }

    /**
     * Get Keras major version of this layer.
     *
     * @return Keras version as integer
     */
    public Integer getKerasMajorVersion() {
        return this.kerasMajorVersion;
    }

    /**
     * Get Keras layer class name.
     *
     * @return Keras layer class name
     */
    public String getClassName() {
        return this.className;
    }

    /**
     * Get Keras layer name.
     *
     * @return layer name
     */
    public String getLayerName() {
        return this.layerName;
    }

    /**
     * Get layer input shape.
     *
     * @return input shape
     */
    public int[] getInputShape() {
        if (this.inputShape == null)
            return null;
        return this.inputShape.clone();
    }

    /**
     * Get Keras layer backend dimension order.
     *
     * @return Keras layer (backend) dimension order
     */
    public DimOrder getDimOrder() {
        return this.dimOrder;
    }

    /**
     * Set Keras layer backend dimension order.
     *
     * @return Keras layer (backend) dimension order
     */
    public void setDimOrder(DimOrder dimOrder) {
        this.dimOrder = dimOrder;
    }

    /**
     * Get list of inbound layers.
     *
     * @return list of inbound layer names
     */
    public List<String> getInboundLayerNames() {
        if (this.inboundLayerNames == null)
            this.inboundLayerNames = new ArrayList<String>();
        return this.inboundLayerNames;
    }

    /**
     * Set list of inbound layers.
     *
     * @param inboundLayerNames list of inbound layer naems
     * @return
     */
    public void setInboundLayerNames(List<String> inboundLayerNames) {
        this.inboundLayerNames = new ArrayList<String>(inboundLayerNames);
    }

    /**
     * Returns number of trainable parameters in layer.
     *
     * @return number of trainable parameters
     */
    public int getNumParams() {
        return 0;
    }

    /**
     * Indicates whether layer uses regularization.
     *
     * @return boolean
     */
    public boolean usesRegularization() {
        return (this.weightL1Regularization > 0.0 || this.weightL2Regularization > 0.0 || this.dropout < 1.0);
    }

    /**
     * Set weights for Keras layer.
     *
     * @param weights
     */
    public void setWeights(Map<String, INDArray> weights) throws InvalidKerasConfigurationException {
        //no op
    }

    /**
     * Copy Keras layer weights to DL4J Layer.
     *
     * @param layer
     * @throws InvalidKerasConfigurationException
     */
    public void copyWeightsToLayer(org.deeplearning4j.nn.api.Layer layer) throws InvalidKerasConfigurationException {
        if (this.getNumParams() > 0) {
            String dl4jLayerName = layer.conf().getLayer().getLayerName();
            String kerasLayerName = this.getLayerName();
            String msg = "Error when attempting to copy weights from Keras layer " + kerasLayerName + " to DL4J layer "
                    + dl4jLayerName;

            if (this.weights == null)
                throw new InvalidKerasConfigurationException(msg + "(weights is null)");

            Set<String> paramsInLayer = new HashSet<String>(layer.paramTable().keySet());
            Set<String> paramsInKerasLayer = new HashSet<String>(this.weights.keySet());

            /* Check for parameters in layer for which we don't have weights. */
            paramsInLayer.removeAll(paramsInKerasLayer);
            for (String paramName : paramsInLayer)
                throw new InvalidKerasConfigurationException(
                        msg + "(no stored weights for parameter " + paramName + ")");

            /* Check for parameters NOT in layer for which we DO have weights. */
            paramsInKerasLayer.removeAll(layer.paramTable().keySet());
            for (String paramName : paramsInKerasLayer)
                throw new InvalidKerasConfigurationException(msg + "(found no parameter named " + paramName + ")");

            /* Copy weights. */
            for (String paramName : layer.paramTable().keySet()) {
                layer.setParam(paramName, this.weights.get(paramName));
            }
        }
    }

    /**
     * Whether this Keras layer maps to a DL4J Layer.
     *
     * @return true or false
     */
    public boolean isLayer() {
        return this.layer != null;
    }

    /**
     * Gets corresponding DL4J Layer, if any.
     *
     * @return DL4J Layer
     * @see org.deeplearning4j.nn.api.Layer
     */
    public Layer getLayer() {
        return this.layer;
    }

    /**
     * Whether this Keras layer maps to a DL4J Vertex.
     *
     * @return true or false
     */
    public boolean isVertex() {
        return this.vertex != null;
    }

    /**
     * Gets corresponding DL4J Vertex, if any.
     *
     * @return DL4J Vertex
     * @see org.deeplearning4j.nn.conf.graph.GraphVertex
     */
    public GraphVertex getVertex() {
        return this.vertex;
    }

    /**
     * Whether this Keras layer maps to a DL4J InputPreProcessor.
     *
     * @return true or false
     */
    public boolean isInputPreProcessor() {
        return false;
    }

    /**
     * Gets appropriate DL4J InputPreProcessor for given InputTypes.
     *
     * @param inputType Array of InputTypes
     * @return DL4J InputPreProcessor
     * @throws InvalidKerasConfigurationException
     * @see org.deeplearning4j.nn.conf.InputPreProcessor
     */
    public InputPreProcessor getInputPreprocessor(InputType... inputType) throws InvalidKerasConfigurationException {
        InputPreProcessor preprocessor = null;
        if (this.layer != null) {
            if (inputType.length > 1)
                throw new InvalidKerasConfigurationException(
                        "Keras layer of type \"" + this.className + "\" accepts only one input");
            preprocessor = this.layer.getPreProcessorForInputType(inputType[0]);
        }
        return preprocessor;
    }

    /**
     * Get layer output type.
     *
     * @param inputType Array of InputTypes
     * @return output type as InputType
     * @throws InvalidKerasConfigurationException
     */
    public InputType getOutputType(InputType... inputType)
            throws InvalidKerasConfigurationException, UnsupportedKerasConfigurationException {
        throw new UnsupportedOperationException(
                "Cannot determine output type for Keras layer of type " + this.className);
    }

    /**
     * Indicates whether this layer a valid inbound layer. Currently, only
     * (known) DL4J Layers and inputs are valid inbound layers. "Preprocessor"
     * layers (reshaping, merging, etc.) are replaced by their own inbound layers.
     * <p>
     * TODO: revisit this once "preprocessor" layers are handled explicitly
     *
     * @return boolean indicating whether layer is valid inbound layer
     * @see org.deeplearning4j.nn.api.Layer
     */
    public boolean isValidInboundLayer() throws InvalidKerasConfigurationException {
        return (getLayer() != null || getVertex() != null || getInputPreprocessor() != null
                || this.className.equals(conf.getLAYER_CLASS_NAME_INPUT()));
    }

    /**
     * Map Keras to DL4J activation functions.
     *
     * @param kerasActivation String containing Keras activation function name
     * @return String containing DL4J activation function name
     */
    public IActivation mapActivation(String kerasActivation) throws UnsupportedKerasConfigurationException {
        IActivation dl4jActivation = null;
        /* Keras and DL4J use the same name for most activations. */
        if (kerasActivation.equals(conf.getKERAS_ACTIVATION_SOFTMAX())) {
            dl4jActivation = new ActivationSoftmax();
        } else if (kerasActivation.equals(conf.getKERAS_ACTIVATION_SOFTPLUS())) {
            dl4jActivation = new ActivationSoftPlus();
        } else if (kerasActivation.equals(conf.getKERAS_ACTIVATION_SOFTSIGN())) {
            dl4jActivation = new ActivationSoftSign();
        } else if (kerasActivation.equals(conf.getKERAS_ACTIVATION_RELU())) {
            dl4jActivation = new ActivationReLU();
        } else if (kerasActivation.equals(conf.getKERAS_ACTIVATION_TANH())) {
            dl4jActivation = new ActivationTanH();
        } else if (kerasActivation.equals(conf.getKERAS_ACTIVATION_SIGMOID())) {
            dl4jActivation = new ActivationSigmoid();
        } else if (kerasActivation.equals(conf.getKERAS_ACTIVATION_HARD_SIGMOID())) {
            dl4jActivation = new ActivationHardSigmoid();
        } else if (kerasActivation.equals(conf.getKERAS_ACTIVATION_LINEAR())) {
            dl4jActivation = new ActivationIdentity();
        } else {
            throw new UnsupportedKerasConfigurationException(
                    "Unknown Keras activation function " + kerasActivation);
        }
        return dl4jActivation;
    }

    /**
     * Map Keras to DL4J weight initialization functions.
     *
     * @param kerasInit String containing Keras initialization function name
     * @return DL4J weight initialization enum
     * @see WeightInit
     */
    public WeightInit mapWeightInitialization(String kerasInit) throws UnsupportedKerasConfigurationException {
        /* WEIGHT INITIALIZATION
         * TODO: finish mapping keras-to-dl4j weight distributions.
         * INIT_IDENTITY INIT_ORTHOGONAL INIT_LECUN_UNIFORM, INIT_NORMAL
         * INIT_VARIANCE_SCALING, INIT_CONSTANT, INIT_ONES
         * Low priority since our focus is on loading trained models.
         * Remaining dl4j distributions: DISTRIBUTION, SIZE, NORMALIZED,VI
         */
        WeightInit init = WeightInit.XAVIER;
        if (kerasInit != null) {
            if (kerasInit.equals(conf.getINIT_GLOROT_NORMAL())) {
                init = WeightInit.XAVIER;
            } else if (kerasInit.equals(conf.getINIT_GLOROT_UNIFORM())) {
                init = WeightInit.XAVIER_UNIFORM;
            } else if (kerasInit.equals(conf.getINIT_LECUN_NORMAL())) {
                init = WeightInit.NORMAL;
            } else if (kerasInit.equals(conf.getINIT_UNIFORM()) ||
                    kerasInit.equals(conf.getINIT_RANDOM_UNIFORM()) ||
                    kerasInit.equals(conf.getINIT_RANDOM_UNIFORM_ALIAS())) {
                init = WeightInit.UNIFORM;
            }  else if (kerasInit.equals(conf.getINIT_HE_NORMAL())) {
                init = WeightInit.RELU;
            } else if (kerasInit.equals(conf.getINIT_HE_UNIFORM())) {
                init = WeightInit.RELU_UNIFORM;
            } else if (kerasInit.equals(conf.getINIT_ZERO()) ||
                    kerasInit.equals(conf.getINIT_ZEROS()) ||
                    kerasInit.equals(conf.getINIT_ZEROS_ALIAS())) {
                init = WeightInit.ZERO;
            } else if (kerasInit.equals(conf.getINIT_VARIANCE_SCALING())) {
                // TODO: This is incorrect, but we need it in tests for now
                init = WeightInit.XAVIER_UNIFORM;
            } else {
                // TODO: implement
                throw new UnsupportedKerasConfigurationException("Unknown keras weight initializer " + kerasInit);
            }
        }
        return init;
    }

    /**
     * Map Keras to DL4J loss functions.
     *
     * @param kerasLoss String containing Keras loss function name
     * @return String containing DL4J loss function
     */
    public LossFunctions.LossFunction mapLossFunction(String kerasLoss)
            throws UnsupportedKerasConfigurationException {
        LossFunctions.LossFunction dl4jLoss;
        if (kerasLoss.equals(conf.getKERAS_LOSS_MEAN_SQUARED_ERROR()) ||
                kerasLoss.equals(conf.getKERAS_LOSS_MSE())) {
            dl4jLoss = LossFunctions.LossFunction.SQUARED_LOSS;
        } else if (kerasLoss.equals(conf.getKERAS_LOSS_MEAN_ABSOLUTE_ERROR()) ||
                kerasLoss.equals(conf.getKERAS_LOSS_MAE())) {
            dl4jLoss = LossFunctions.LossFunction.MEAN_ABSOLUTE_ERROR;
        } else if (kerasLoss.equals(conf.getKERAS_LOSS_MEAN_ABSOLUTE_PERCENTAGE_ERROR()) ||
                kerasLoss.equals(conf.getKERAS_LOSS_MAPE())) {
            dl4jLoss = LossFunctions.LossFunction.MEAN_ABSOLUTE_PERCENTAGE_ERROR;
        } else if (kerasLoss.equals(conf.getKERAS_LOSS_MEAN_SQUARED_LOGARITHMIC_ERROR()) ||
                kerasLoss.equals(conf.getKERAS_LOSS_MSLE())) {
            dl4jLoss = LossFunctions.LossFunction.MEAN_SQUARED_LOGARITHMIC_ERROR;
        } else if (kerasLoss.equals(conf.getKERAS_LOSS_SQUARED_HINGE())) {
            dl4jLoss = LossFunctions.LossFunction.SQUARED_HINGE;
        } else if (kerasLoss.equals(conf.getKERAS_LOSS_HINGE())) {
            dl4jLoss = LossFunctions.LossFunction.HINGE;
        } else if (kerasLoss.equals(conf.getKERAS_LOSS_SPARSE_CATEGORICAL_CROSSENTROPY())) {
            throw new UnsupportedKerasConfigurationException("Loss function " + kerasLoss + " not supported yet.");
        } else if (kerasLoss.equals(conf.getKERAS_LOSS_BINARY_CROSSENTROPY())) {
            dl4jLoss = LossFunctions.LossFunction.XENT;
        } else if (kerasLoss.equals(conf.getKERAS_LOSS_CATEGORICAL_CROSSENTROPY())) {
            dl4jLoss = LossFunctions.LossFunction.MCXENT;
        } else if (kerasLoss.equals(conf.getKERAS_LOSS_KULLBACK_LEIBLER_DIVERGENCE()) ||
                kerasLoss.equals(conf.getKERAS_LOSS_KLD())) {
            dl4jLoss = LossFunctions.LossFunction.KL_DIVERGENCE;
        } else if (kerasLoss.equals(conf.getKERAS_LOSS_POISSON())) {
            dl4jLoss = LossFunctions.LossFunction.POISSON;
        } else if (kerasLoss.equals(conf.getKERAS_LOSS_COSINE_PROXIMITY())) {
            dl4jLoss = LossFunctions.LossFunction.COSINE_PROXIMITY;
        } else {
            throw new UnsupportedKerasConfigurationException("Unknown Keras loss function " + kerasLoss);
        }
        return dl4jLoss;
    }

    /**
     * Map Keras pooling layers to DL4J pooling types.
     *
     * @param className
     * @return
     * @throws UnsupportedKerasConfigurationException
     */
    public PoolingType mapPoolingType(String className) throws UnsupportedKerasConfigurationException {
        PoolingType poolingType;
        if (className.equals(conf.getLAYER_CLASS_NAME_MAX_POOLING_2D()) ||
                className.equals(conf.getLAYER_CLASS_NAME_GLOBAL_MAX_POOLING_1D()) ||
                className.equals(conf.getLAYER_CLASS_NAME_GLOBAL_MAX_POOLING_2D())) {
            poolingType = PoolingType.MAX;
        } else if (className.equals(conf.getLAYER_CLASS_NAME_AVERAGE_POOLING_2D()) ||
                className.equals(conf.getLAYER_CLASS_NAME_GLOBAL_AVERAGE_POOLING_1D()) ||
                className.equals(conf.getLAYER_CLASS_NAME_GLOBAL_AVERAGE_POOLING_2D())) {
            poolingType = PoolingType.AVG;
        } else {
            throw new UnsupportedKerasConfigurationException("Unsupported Keras pooling layer " + className);
        }
        return poolingType;
    }

    /**
     * Map Keras pooling layers to DL4J pooling dimensions.
     *
     * @param className
     * @return
     * @throws UnsupportedKerasConfigurationException
     */
    public int[] mapPoolingDimensions(String className) throws UnsupportedKerasConfigurationException {
        int[] dimensions;
        if (className.equals(conf.getLAYER_CLASS_NAME_GLOBAL_MAX_POOLING_1D()) ||
                className.equals(conf.getLAYER_CLASS_NAME_GLOBAL_AVERAGE_POOLING_1D())) {
            dimensions = new int[]{2};
        } else if (className.equals(conf.getLAYER_CLASS_NAME_GLOBAL_MAX_POOLING_2D()) ||
                className.equals(conf.getLAYER_CLASS_NAME_GLOBAL_AVERAGE_POOLING_2D())) {
            dimensions = new int[]{2, 3};
        } else {
            throw new UnsupportedKerasConfigurationException("Unsupported Keras pooling layer " + className);
        }
        return dimensions;
    }

    /**
     * Get activation function from Keras layer configuration.
     *
     * @param layerConfig dictionary containing Keras layer configuration
     * @return
     * @throws InvalidKerasConfigurationException
     * @throws UnsupportedKerasConfigurationException
     */
    protected IActivation getActivationFromConfig(Map<String, Object> layerConfig)
            throws InvalidKerasConfigurationException, UnsupportedKerasConfigurationException {
        Map<String, Object> innerConfig = KerasLayerUtils.getInnerLayerConfigFromConfig(layerConfig, conf);
        if (!innerConfig.containsKey(conf.getLAYER_FIELD_ACTIVATION()))
            throw new InvalidKerasConfigurationException("Keras layer is missing "
                    + conf.getLAYER_FIELD_ACTIVATION() + " field");
        return mapActivation((String) innerConfig.get(conf.getLAYER_FIELD_ACTIVATION()));
    }

    /**
     * Get weight initialization from Keras layer configuration.
     *
     * @param layerConfig           dictionary containing Keras layer configuration
     * @param enforceTrainingConfig
     * @return
     * @throws InvalidKerasConfigurationException
     * @throws UnsupportedKerasConfigurationException
     */
    protected WeightInit getWeightInitFromConfig(Map<String, Object> layerConfig, String initField,
                                                 boolean enforceTrainingConfig)
            throws InvalidKerasConfigurationException, UnsupportedKerasConfigurationException {
        Map<String, Object> innerConfig = KerasLayerUtils.getInnerLayerConfigFromConfig(layerConfig, conf);
        if (!innerConfig.containsKey(initField))
            throw new InvalidKerasConfigurationException("Keras layer is missing " + initField + " field");
        String kerasInit = "glorot_normal";
        if (kerasMajorVersion != 2)
            kerasInit = (String) innerConfig.get(initField);
        else {
            HashMap initMap = (HashMap) innerConfig.get(initField);
            if (initMap.containsKey("class_name")) {
                kerasInit = (String) initMap.get("class_name");
            }
        }
        WeightInit init;
        try {
            init = mapWeightInitialization(kerasInit);
        } catch (UnsupportedKerasConfigurationException e) {
            if (enforceTrainingConfig)
                throw e;
            else {
                init = WeightInit.XAVIER;
                log.warn("Unknown weight initializer " + kerasInit + " (Using XAVIER instead).");
            }
        }
        return init;
    }

    /**
     * Get L1 weight regularization (if any) from Keras weight regularization configuration.
     *
     * @param layerConfig dictionary containing Keras layer configuration     Map containing Keras weight reguarlization configuration
     * @return L1 regularization strength (0.0 if none)
     */
    public double getWeightL1RegularizationFromConfig(Map<String, Object> layerConfig, boolean willBeTrained)
            throws UnsupportedKerasConfigurationException, InvalidKerasConfigurationException {
        Map<String, Object> innerConfig = KerasLayerUtils.getInnerLayerConfigFromConfig(layerConfig, conf);
        if (innerConfig.containsKey(conf.getLAYER_FIELD_W_REGULARIZER())) {
            Map<String, Object> regularizerConfig =
                    (Map<String, Object>) innerConfig.get(conf.getLAYER_FIELD_W_REGULARIZER());
            if (regularizerConfig != null && regularizerConfig.containsKey(conf.getREGULARIZATION_TYPE_L1()))
                return (double) regularizerConfig.get(conf.getREGULARIZATION_TYPE_L1());
        }
        return 0.0;
    }

    /**
     * Get L2 weight regularization (if any) from Keras weight regularization configuration.
     *
     * @param layerConfig dictionary containing Keras layer configuration
     * @return L1 regularization strength (0.0 if none)
     */
    public double getWeightL2RegularizationFromConfig(Map<String, Object> layerConfig, boolean willBeTrained)
            throws UnsupportedKerasConfigurationException, InvalidKerasConfigurationException {
        Map<String, Object> innerConfig = KerasLayerUtils.getInnerLayerConfigFromConfig(layerConfig, conf);
        if (innerConfig.containsKey(conf.getLAYER_FIELD_W_REGULARIZER())) {
            Map<String, Object> regularizerConfig =
                    (Map<String, Object>) innerConfig.get(conf.getLAYER_FIELD_W_REGULARIZER());
            if (regularizerConfig != null && regularizerConfig.containsKey(conf.getREGULARIZATION_TYPE_L2()))
                return (double) regularizerConfig.get(conf.getREGULARIZATION_TYPE_L2());
        }
        return 0.0;
    }

    /**
     * Get (convolution) stride from Keras layer configuration.
     *
     * @param layerConfig dictionary containing Keras layer configuration
     * @return
     * @throws InvalidKerasConfigurationException
     */
    public int[] getStrideFromConfig(Map<String, Object> layerConfig, int dimension) throws InvalidKerasConfigurationException {
        Map<String, Object> innerConfig = KerasLayerUtils.getInnerLayerConfigFromConfig(layerConfig, conf);
        int[] strides = null;
        if (innerConfig.containsKey(conf.getLAYER_FIELD_CONVOLUTION_STRIDES()) && dimension == 2) {
            /* 2D Convolutional layers. */
            List<Integer> stridesList = (List<Integer>) innerConfig.get(conf.getLAYER_FIELD_CONVOLUTION_STRIDES());
            strides = ArrayUtil.toArray(stridesList);
        } else if (innerConfig.containsKey(conf.getLAYER_FIELD_SUBSAMPLE_LENGTH()) && dimension == 1) {
           /* 1D Convolutional layers. */
            int subsample_length = (int) innerConfig.get(conf.getLAYER_FIELD_SUBSAMPLE_LENGTH());
            strides = new int[]{subsample_length};
        } else if (innerConfig.containsKey(conf.getLAYER_FIELD_POOL_STRIDES())) {
            /* Pooling layers. */
            List<Integer> stridesList = (List<Integer>) innerConfig.get(conf.getLAYER_FIELD_POOL_STRIDES());
            strides = ArrayUtil.toArray(stridesList);
        } else
            throw new InvalidKerasConfigurationException("Could not determine layer stride: no "
                    + conf.getLAYER_FIELD_CONVOLUTION_STRIDES() + " or "
                    + conf.getLAYER_FIELD_POOL_STRIDES() + " field found");
        return strides;
    }

    /**
     * Get (convolution) kernel size from Keras layer configuration.
     *
     * @param layerConfig dictionary containing Keras layer configuration
     * @return
     * @throws InvalidKerasConfigurationException
     */
    public int[] getKernelSizeFromConfig(Map<String, Object> layerConfig, int dimension)
            throws InvalidKerasConfigurationException {
        Map<String, Object> innerConfig = KerasLayerUtils.getInnerLayerConfigFromConfig(layerConfig, conf);
        int[] kernelSize = null;
        if (kerasMajorVersion != 2) {
            if (innerConfig.containsKey(conf.getLAYER_FIELD_NB_ROW()) && dimension == 2
                    && innerConfig.containsKey(conf.getLAYER_FIELD_NB_COL())) {
            /* 2D Convolutional layers. */
                List<Integer> kernelSizeList = new ArrayList<Integer>();
                kernelSizeList.add((Integer) innerConfig.get(conf.getLAYER_FIELD_NB_ROW()));
                kernelSizeList.add((Integer) innerConfig.get(conf.getLAYER_FIELD_NB_COL()));
                kernelSize = ArrayUtil.toArray(kernelSizeList);
            } else if (innerConfig.containsKey(conf.getLAYER_FIELD_FILTER_LENGTH()) && dimension == 1) {
            /* 1D Convolutional layers. */
                int filter_length = (int) innerConfig.get(conf.getLAYER_FIELD_FILTER_LENGTH());
                kernelSize = new int[]{ filter_length };
            } else if (innerConfig.containsKey(conf.getLAYER_FIELD_POOL_SIZE())) {
            /* Pooling layers. */
                List<Integer> kernelSizeList = (List<Integer>) innerConfig.get(conf.getLAYER_FIELD_POOL_SIZE());
                kernelSize = ArrayUtil.toArray(kernelSizeList);
            } else {
                throw new InvalidKerasConfigurationException("Could not determine kernel size: no "
                        + conf.getLAYER_FIELD_NB_ROW() + ", "
                        + conf.getLAYER_FIELD_NB_COL() + ", or "
                        + conf.getLAYER_FIELD_FILTER_LENGTH() + ", or "
                        + conf.getLAYER_FIELD_POOL_SIZE() + " field found");
            }
        } else {
            /* 2D Convolutional layers. */
            if (innerConfig.containsKey(conf.getLAYER_FIELD_KERNEL_SIZE()) && dimension == 2) {
                List<Integer> kernelSizeList = (List<Integer>) innerConfig.get(conf.getLAYER_FIELD_KERNEL_SIZE());
                kernelSize = ArrayUtil.toArray(kernelSizeList);
            } else if (innerConfig.containsKey(conf.getLAYER_FIELD_FILTER_LENGTH()) && dimension == 1) {
            /* 1D Convolutional layers. */
                int filter_length = (int) innerConfig.get(conf.getLAYER_FIELD_FILTER_LENGTH());
                kernelSize = new int[]{ filter_length };
            } else if (innerConfig.containsKey(conf.getLAYER_FIELD_POOL_SIZE())) {
            /* Pooling layers. */
                List<Integer> kernelSizeList = (List<Integer>) innerConfig.get(conf.getLAYER_FIELD_POOL_SIZE());
                kernelSize = ArrayUtil.toArray(kernelSizeList);
            } else {
                throw new InvalidKerasConfigurationException("Could not determine kernel size: no "
                        + conf.getLAYER_FIELD_KERNEL_SIZE() + ", or "
                        + conf.getLAYER_FIELD_FILTER_LENGTH() + ", or "
                        + conf.getLAYER_FIELD_POOL_SIZE() + " field found");
            }
        }

        return kernelSize;
    }

    /**
     * Get convolution border mode from Keras layer configuration.
     *
     * @param layerConfig dictionary containing Keras layer configuration
     * @return
     * @throws InvalidKerasConfigurationException
     */
    public ConvolutionMode getConvolutionModeFromConfig(Map<String, Object> layerConfig)
            throws InvalidKerasConfigurationException, UnsupportedKerasConfigurationException {
        Map<String, Object> innerConfig = KerasLayerUtils.getInnerLayerConfigFromConfig(layerConfig, conf);
        if (!innerConfig.containsKey(conf.getLAYER_FIELD_BORDER_MODE()))
            throw new InvalidKerasConfigurationException("Could not determine convolution border mode: no "
                    + conf.getLAYER_FIELD_BORDER_MODE() + " field found");
        String borderMode = (String) innerConfig.get(conf.getLAYER_FIELD_BORDER_MODE());
        ConvolutionMode convolutionMode = null;
        if (borderMode.equals(conf.getLAYER_BORDER_MODE_SAME())) {
            /* Keras relies upon the Theano and TensorFlow border mode definitions
             * and operations:
             * - Theano: http://deeplearning.net/software/theano/library/tensor/nnet/conv.html#theano.tensor.nnet.conv.conv2d
             * - TensorFlow: https://www.tensorflow.org/api_docs/python/nn/convolution#conv2d
             */
            convolutionMode = ConvolutionMode.Same;

        } else if (borderMode.equals(conf.getLAYER_BORDER_MODE_VALID()) ||
                borderMode.equals(conf.getLAYER_BORDER_MODE_VALID())) {
            /* TensorFlow and Theano "valid" modes apply filter only
             * to complete patches within the image borders with no
             * padding. That is equivalent to DL4J Truncate mode
             * with no padding.
             */
            /* Theano-only "full" mode zero pads the image so that
             * outputs = (inputs + filters + 1) / stride. This should
             * be equivalent to DL4J Truncate mode with padding
             * equal to filters-1.
             * TODO: verify this is correct.
             */
            convolutionMode = ConvolutionMode.Truncate;

        } else {
            throw new UnsupportedKerasConfigurationException("Unsupported convolution border mode: " + borderMode);
        }
        return convolutionMode;
    }

    /**
     * Get (convolution) padding from Keras layer configuration.
     *
     * @param layerConfig dictionary containing Keras layer configuration
     * @return
     * @throws InvalidKerasConfigurationException
     */
    public int[] getPaddingFromBorderModeConfig(Map<String, Object> layerConfig, int dimension)
            throws InvalidKerasConfigurationException, UnsupportedKerasConfigurationException {
        Map<String, Object> innerConfig = KerasLayerUtils.getInnerLayerConfigFromConfig(layerConfig, conf);
        int[] padding = null;
        if (!innerConfig.containsKey(conf.getLAYER_FIELD_BORDER_MODE()))
            throw new InvalidKerasConfigurationException("Could not determine convolution border mode: no "
                    + conf.getLAYER_FIELD_BORDER_MODE() + " field found");
        String borderMode = (String) innerConfig.get(conf.getLAYER_FIELD_BORDER_MODE());
        if (borderMode == conf.getLAYER_FIELD_BORDER_MODE()) {
            padding = getKernelSizeFromConfig(layerConfig, dimension);
            for (int i = 0; i < padding.length; i++)
                padding[i]--;
        }
        return padding;
    }
}
