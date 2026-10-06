import { ComponentProps } from 'react';
import { ApiBlueprint, createFrontendModule } from '@backstage/frontend-plugin-api';
import {
  apiDocsConfigRef,
  defaultDefinitionWidgets,
  OpenApiDefinitionWidget,
} from '@backstage/plugin-api-docs';

/**
 * `OpenApiDefinitionWidgetProps` declares only definition / requestInterceptor / supportedSubmitMethods,
 * but the widget spreads every other prop onto `<SwaggerUI>` (OpenApiDefinition.esm.js), so Swagger
 * UI's own options pass straight through.
 */
const SwaggerWidget = OpenApiDefinitionWidget as (
  props: ComponentProps<typeof OpenApiDefinitionWidget> & { filter?: boolean | string },
) => JSX.Element;

/**
 * Replaces the plugin's `api:api-docs/config` (same plugin id + name) so the OpenAPI definition renders:
 *  - with Swagger UI's `filter` box — filters the operation list by OpenAPI tag;
 *  - read-only — no "Try it out": the portal documents the services, it does not call them, and a
 *    request from here would go from the browser to whatever `servers:` names.
 * Every other definition type keeps the plugin's default widget.
 */
const apiDocsConfig = ApiBlueprint.make({
  name: 'config',
  params: defineParams =>
    defineParams({
      api: apiDocsConfigRef,
      deps: {},
      factory: () => {
        const widgets = defaultDefinitionWidgets().map(widget =>
          widget.type === 'openapi'
            ? {
                ...widget,
                component: (definition: string) => (
                  <SwaggerWidget definition={definition} filter supportedSubmitMethods={[]} />
                ),
              }
            : widget,
        );
        return {
          getApiDefinitionWidget: apiEntity => widgets.find(w => w.type === apiEntity.spec.type),
        };
      },
    }),
});

export const apiDocsModule = createFrontendModule({
  pluginId: 'api-docs',
  extensions: [apiDocsConfig],
});
