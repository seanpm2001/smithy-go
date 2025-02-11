/*
 * Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.go.codegen.endpoints;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.List;
import java.util.Optional;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.MiddlewareIdentifier;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ToShapeId;
import software.amazon.smithy.rulesengine.language.EndpointRuleSet;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameter;
import software.amazon.smithy.rulesengine.language.syntax.parameters.ParameterType;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameters;
import software.amazon.smithy.rulesengine.traits.ClientContextParamsTrait;
import software.amazon.smithy.rulesengine.traits.ContextParamTrait;
import software.amazon.smithy.rulesengine.traits.EndpointRuleSetTrait;
import software.amazon.smithy.rulesengine.traits.StaticContextParamsTrait;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyBuilder;
import software.amazon.smithy.utils.StringUtils;


/**
 * Class responsible for generating middleware
 * that will be used during endpoint resolution.
 */
public final class EndpointMiddlewareGenerator {
    // TODO(ep20): remove existing v1 "ResolveEndpoint" and rename
    public static final String MIDDLEWARE_ID = "ResolveEndpointV2";

    List<GoIntegration> integrations;

    private EndpointMiddlewareGenerator(Builder builder) {
        this.integrations = SmithyBuilder.requiredState("integrations", builder.integrations);
    }


    public void generate(GoSettings settings, Model model, SymbolProvider symbolProvider, GoDelegator goDelegator) {
        var serviceShape = settings.getService(model);

        var rulesetTrait = serviceShape.getTrait(EndpointRuleSetTrait.class);
        var clientContextParamsTrait = serviceShape.getTrait(ClientContextParamsTrait.class);

        Optional<EndpointRuleSet> rulesetOpt = (rulesetTrait.isPresent())
                ? Optional.of(EndpointRuleSet.fromNode(rulesetTrait.get().getRuleSet()))
                : Optional.empty();

        TopDownIndex topDownIndex = TopDownIndex.of(model);

        for (ToShapeId operation : topDownIndex.getContainedOperations(serviceShape)) {
            OperationShape operationShape = model.expectShape(operation.toShapeId(), OperationShape.class);
            goDelegator.useShapeWriter(operationShape, writer -> {
                if (rulesetOpt.isPresent()) {
                    var parameters = rulesetOpt.get().getParameters();
                    Symbol operationSymbol = symbolProvider.toSymbol(operationShape);
                    String operationName = operationSymbol.getName();
                    writer.write(
                        """
                        $W

                        $W

                        $W
                        """,
                        generateMiddlewareType(parameters, clientContextParamsTrait, operationName),
                        generateMiddlewareMethods(
                            parameters, settings, clientContextParamsTrait, symbolProvider, operationShape, model),
                        generateMiddlewareAdder(parameters, operationName, clientContextParamsTrait)
                    );
                }
            });
        }
    }

    private GoWriter.Writable generateMiddlewareType(
        Parameters parameters, Optional<ClientContextParamsTrait> clientContextParamsTrait, String operationName) {
        return (GoWriter w) -> {
            w.openBlock("type $L struct {", "}", getMiddlewareObjectName(operationName), () -> {
                w.write("EndpointResolver $T", SymbolUtils.createValueSymbolBuilder("EndpointResolverV2").build());
                for (Parameter param : parameters.toList()) {
                    if (param.getBuiltIn().isPresent()) {
                        for (GoIntegration integration : this.integrations) {
                            var builtInHandlerOpt = integration.getEndpointBuiltinHandler();
                            if (builtInHandlerOpt.isPresent()) {
                                builtInHandlerOpt.get().renderEndpointBuiltInField(w);
                            }
                        }
                        break;
                    }
                }


                if (clientContextParamsTrait.isPresent()) {
                    var clientContextParams = clientContextParamsTrait.get();
                    parameters.toList().stream().forEach(param -> {
                        if (clientContextParams.getParameters().containsKey(param.getName().asString())
                        && !param.getBuiltIn().isPresent()) {
                            w.write("$L $P", getExportedParameterName(param), parameterAsSymbol(param));
                        }
                    });
                }

            });
        };
    }

    private GoWriter.Writable generateMiddlewareMethods(
        Parameters parameters, GoSettings settings,
        Optional<ClientContextParamsTrait> clientContextParamsTrait,
        SymbolProvider symbolProvider, OperationShape operationShape, Model model) {

        Symbol operationSymbol = symbolProvider.toSymbol(operationShape);
        String operationName = operationSymbol.getName();
        String middlewareName = getMiddlewareObjectName(operationName);
        Symbol middlewareSymbol = SymbolUtils.createPointableSymbolBuilder(middlewareName).build();
        return (GoWriter writer) -> {
            writer.openBlock("func ($P) ID() string {", "}", middlewareSymbol, () -> {
                writer.writeInline("return ");
                MiddlewareIdentifier.string(MIDDLEWARE_ID).writeInline(writer);
                writer.write("");
            });

            writer.write("");

            String handleMethodName = "HandleSerialize";
            Symbol contextType =
                SymbolUtils.createValueSymbolBuilder("Context", SmithyGoDependency.CONTEXT).build();
            Symbol metadataType =
                SymbolUtils.createValueSymbolBuilder("Metadata", SmithyGoDependency.SMITHY_MIDDLEWARE).build();
            var inputType =
                SymbolUtils.createValueSymbolBuilder("SerializeInput", SmithyGoDependency.SMITHY_MIDDLEWARE).build();
            var outputType =
                SymbolUtils.createValueSymbolBuilder("SerializeOutput", SmithyGoDependency.SMITHY_MIDDLEWARE).build();
            var handlerType =
                SymbolUtils.createValueSymbolBuilder("SerializeHandler", SmithyGoDependency.SMITHY_MIDDLEWARE).build();


            writer.openBlock("func (m $P) $L(ctx $T, in $T, next $T) (\n"
                            + "\tout $T, metadata $T, err error,\n"
                            + ") {", "}",
                    new Object[]{
                            middlewareSymbol, handleMethodName, contextType, inputType, handlerType, outputType,
                            metadataType,
                    },
                    () -> {
                        writer.write("$W",
                            generateMiddlewareResolverBody(
                                operationShape, model, parameters, clientContextParamsTrait, settings)
                        );
                    });
        };
    }

    private GoWriter.Writable generateMiddlewareResolverBody(
        OperationShape operationShape, Model model, Parameters parameters,
        Optional<ClientContextParamsTrait> clientContextParamsTrait,
        GoSettings settings) {
        return goTemplate(
            """
            $preEndpointResolutionHook:W

            $requestValidator:W

            $inputValidator:W

            $legacyResolverValidator:W

            params := $endpointParametersType:L{}

            $builtInResolverInvocation:W

            $clientContextBinding:W

            $contextBinding:W

            $staticContextBinding:W

            $endpointResolution:W

            $postEndpointResolution:W

            return next.HandleSerialize(ctx, in)

            """,
            MapUtils.of(
                "preEndpointResolutionHook", generatePreEndpointResolutionHook(settings, model)
            ),
            MapUtils.of(
                "requestValidator", generateRequestValidator(),
                "inputValidator", generateInputValidator(model, operationShape),
                "legacyResolverValidator", generateLegacyResolverValidator(),
                "endpointParametersType", EndpointResolutionGenerator.PARAMETERS_TYPE_NAME,
                "builtInResolverInvocation", generateBuiltInResolverInvocation(parameters),
                "clientContextBinding", generateClientContextParamBinding(parameters, clientContextParamsTrait),
                "contextBinding", generateContextParamBinding(operationShape, model),
                "staticContextBinding", generateStaticContextParamBinding(parameters, operationShape),
                "endpointResolution", generateEndpointResolution(),
                "postEndpointResolution", generatePostEndpointResolutionHook(settings, model, operationShape)
            )
        );
    }

    private GoWriter.Writable generatePreEndpointResolutionHook(GoSettings settings, Model model) {
        return (GoWriter writer) -> {
            for (GoIntegration integration : this.integrations) {
                integration.renderPreEndpointResolutionHook(settings, writer, model);
            }
        };
    }

    private GoWriter.Writable generateRequestValidator() {
        return (GoWriter writer) -> {
            writer.write(
                """
                    req, ok := in.Request.($P)
                    if !ok {
                        return out, metadata, $T(\"unknown transport type %T\", in.Request)
                    }
                """,
                SymbolUtils.createPointableSymbolBuilder("Request", SmithyGoDependency.SMITHY_HTTP_TRANSPORT).build(),
                SymbolUtils.createValueSymbolBuilder("Errorf", SmithyGoDependency.FMT).build()
            );
        };
    }

    private GoWriter.Writable generateInputValidator(Model model, OperationShape operationShape) {
        var opIndex = OperationIndex.of(model);
        var inputOpt = opIndex.getInput(operationShape);
        GoWriter.Writable inputValidator = (GoWriter writer) -> {
            writer.write("");
        };

        if (inputOpt.isPresent()) {
            var input = inputOpt.get();
            for (var inputMember : input.getAllMembers().values()) {
                var contextParamTraitOpt = inputMember.getTrait(ContextParamTrait.class);
                if (contextParamTraitOpt.isPresent()) {
                    inputValidator = (GoWriter writer) -> {
                        writer.write(
                            """
                                input, ok := in.Parameters.($P)
                                if !ok {
                                    return out, metadata, $T(\"unknown transport type %T\", in.Request)
                                }
                            """,
                            SymbolUtils.createPointableSymbolBuilder(operationShape.getInput().get().getName()).build(),
                            SymbolUtils.createValueSymbolBuilder("Errorf", SmithyGoDependency.FMT).build()
                        );
                    };
                }
            }
        }
        return inputValidator;
    }

    private GoWriter.Writable generateLegacyResolverValidator() {
        return (GoWriter writer) -> {
            writer.write(
                """
                    if m.EndpointResolver == nil {
                        return out, metadata, $T(\"expected endpoint resolver to not be nil\")
                    }
                """,
                SymbolUtils.createValueSymbolBuilder("Errorf", SmithyGoDependency.FMT).build()
            );
        };
    }

    private GoWriter.Writable generateBuiltInResolverInvocation(Parameters parameters) {
        return (GoWriter writer) -> {
            for (Parameter parameter : parameters.toList()) {
                if (parameter.getBuiltIn().isPresent()) {
                    for (GoIntegration integration : this.integrations) {
                        var builtInHandlerOpt = integration.getEndpointBuiltinHandler();
                        if (builtInHandlerOpt.isPresent()) {
                            builtInHandlerOpt.get().renderEndpointBuiltInInvocation(writer);
                        }
                    }
                    break;
                }
            }
        };
    }

    private GoWriter.Writable generateClientContextParamBinding(
        Parameters parameters, Optional<ClientContextParamsTrait> clientContextParamsTrait) {

        return (GoWriter writer) -> {
            if (clientContextParamsTrait.isPresent()) {
                var clientContextParams = clientContextParamsTrait.get();
                parameters.toList().stream().forEach(param -> {
                    if (clientContextParams.getParameters().containsKey(param.getName().asString())
                    && !param.getBuiltIn().isPresent()
                    ) {
                        var name = getExportedParameterName(param);
                        writer.write("params.$L = m.$L", name, name);
                    }
                });
            }
        };
    }

    private GoWriter.Writable generateContextParamBinding(OperationShape operationShape, Model model) {
        return (GoWriter writer) -> {
            var opIndex = OperationIndex.of(model);
            var inputOpt = opIndex.getInput(operationShape);
            if (inputOpt.isPresent()) {
                var input = inputOpt.get();
                input.getAllMembers().values().forEach(inputMember -> {
                    var contextParamTraitOpt = inputMember.getTrait(ContextParamTrait.class);
                    if (contextParamTraitOpt.isPresent()) {
                        var contextParamTrait = contextParamTraitOpt.get();
                        writer.write(
                            """
                            params.$L = input.$L
                            """,
                            contextParamTrait.getName(),
                            inputMember.getMemberName()
                        );
                        writer.write("");
                    }
                });
            }
            writer.write("");
        };
    }

    private GoWriter.Writable generateStaticContextParamBinding(Parameters parameters, OperationShape operationShape) {
        var staticContextParamTraitOpt = operationShape.getTrait(StaticContextParamsTrait.class);
        return (GoWriter writer) -> {
            parameters.toList().stream().forEach(param -> {
                if (staticContextParamTraitOpt.isPresent()) {
                    var paramName = param.getName().asString();

                    var staticParam = staticContextParamTraitOpt
                                            .get()
                                            .getParameters()
                                            .get(paramName);
                    if (staticParam != null) {
                        Symbol valueWrapper;
                        if (param.getType() == ParameterType.BOOLEAN) {
                            valueWrapper = SymbolUtils.createValueSymbolBuilder(
                                "Bool", SmithyGoDependency.SMITHY_PTR).build();
                            writer.write(
                                "params.$L = $T($L)", paramName, valueWrapper, staticParam.getValue());
                        } else if (param.getType() == ParameterType.STRING) {
                            valueWrapper = SymbolUtils.createValueSymbolBuilder(
                                "String", SmithyGoDependency.SMITHY_PTR).build();
                            writer.write(
                                "params.$L = $T($L)", paramName, valueWrapper, String.format(
                                    "\"%s\"", staticParam.getValue()
                                ));

                        } else {
                            throw new CodegenException(
                                String.format("unexpected static context param type: %s", param.getType()));
                        }
                    }
                }
            });
            writer.write("");
        };
    }

    private GoWriter.Writable generateEndpointResolution() {
        return goTemplate(
            """
                var resolvedEndpoint $endpointType:T
                resolvedEndpoint, err = m.EndpointResolver.ResolveEndpoint(ctx, params)
                if err != nil {
                    return out, metadata, $errorType:T(\"failed to resolve service endpoint, %w\", err)
                }

                req.URL = &resolvedEndpoint.URI

                for k := range resolvedEndpoint.Headers {
                    req.Header.Set(
                        k,
                        resolvedEndpoint.Headers.Get(k),
                    )
                }

            """,
             MapUtils.of(
                "endpointType", SymbolUtils.createValueSymbolBuilder(
                    "Endpoint", SmithyGoDependency.SMITHY_ENDPOINTS
                ).build(),
                "errorType", SymbolUtils.createValueSymbolBuilder("Errorf", SmithyGoDependency.FMT).build()
            )
        );
    }

    private GoWriter.Writable generatePostEndpointResolutionHook(
        GoSettings settings, Model model, OperationShape operation
    ) {
        return (GoWriter writer) -> {
            for (GoIntegration integration : this.integrations) {
                integration.renderPostEndpointResolutionHook(settings, writer, model, Optional.of(operation));
            }
        };
    }


    private GoWriter.Writable generateMiddlewareAdder(
        Parameters parameters, String operationName, Optional<ClientContextParamsTrait> clientContextParamsTrait) {

        return (GoWriter writer) -> {
            writer.write(
                """
                func $L(stack $P, options Options) error {
                    return stack.Serialize.Insert(&$L{
                        EndpointResolver: options.EndpointResolverV2,
                        $W
                        $W
                    }, \"ResolveEndpoint\", middleware.After)
                }
                """,
                SymbolUtils.createValueSymbolBuilder(getAddEndpointMiddlewareFuncName(operationName)).build(),
                SymbolUtils.createPointableSymbolBuilder("Stack", SmithyGoDependency.SMITHY_MIDDLEWARE).build(),
                SymbolUtils.createValueSymbolBuilder(getMiddlewareObjectName(operationName)).build(),
                generateBuiltInInitialization(parameters),
                generateClientContextParamInitialization(parameters, clientContextParamsTrait)
            );
        };
    }

    private GoWriter.Writable generateBuiltInInitialization(Parameters parameters) {
        return (GoWriter writer) -> {
            for (Parameter parameter : parameters.toList()) {
                if (parameter.getBuiltIn().isPresent()) {
                    for (GoIntegration integration : this.integrations) {
                        var builtInHandlerOpt = integration.getEndpointBuiltinHandler();
                        if (builtInHandlerOpt.isPresent()) {
                            builtInHandlerOpt.get().renderEndpointBuiltInInitialization(writer, parameters);
                        }
                    }
                    break;
                }
            }
        };
    }

    private GoWriter.Writable generateClientContextParamInitialization(
        Parameters parameters, Optional<ClientContextParamsTrait> clientContextParamsTrait) {

        return (GoWriter writer) -> {
            if (clientContextParamsTrait.isPresent()) {
                var clientContextParams = clientContextParamsTrait.get();
                parameters.toList().stream().forEach(param -> {
                    if (
                        clientContextParams.getParameters().containsKey(param.getName().asString())
                        && !param.getBuiltIn().isPresent()
                    ) {
                        var name = getExportedParameterName(param);
                        writer.write("$L: options.$L,", name, name);
                    }
                });
            }
        };
    }

    public static String getAddEndpointMiddlewareFuncName(String operationName) {
        return String.format("add%sResolveEndpointMiddleware", operationName);
    }


    public static String getMiddlewareObjectName(String operationName) {
        return String.format("op%sResolveEndpointMiddleware", operationName);
    }

    public static String getExportedParameterName(Parameter parameter) {
        return StringUtils.capitalize(parameter.getName().asString());
    }

    public static Symbol parameterAsSymbol(Parameter parameter) {
        return switch (parameter.getType()) {
            case STRING -> SymbolUtils.createPointableSymbolBuilder("string")
                    .putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true).build();

            case BOOLEAN -> SymbolUtils.createPointableSymbolBuilder("bool")
                    .putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true).build();
        };
    }


    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements SmithyBuilder<EndpointMiddlewareGenerator> {

        List<GoIntegration> integrations;

        private Builder() {
        }

        public Builder integrations(List<GoIntegration> integrations) {
            this.integrations = integrations;
            return this;
        }


        @Override
        public EndpointMiddlewareGenerator build() {
            return new EndpointMiddlewareGenerator(this);
        }
    }
}
