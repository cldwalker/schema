Title: {{title}}
Date: {{date}}
Tags: {{tags|join:","}}
{% if description %}Description: {{description|safe}}
{% endif %}{% comment %}
Sections are html blocks so markdown doesn't autolink url values inside <a>.
Html blocks end at a blank line, so <ul> blocks must not contain one.
{% endcomment %}{% if breadcrumbs %}
<p class="breadcrumbs">{% for crumb in breadcrumbs %}<a{% if crumb.external? %} class="external" target="_blank" rel="noopener"{% endif %} href="{{crumb.url}}">{{crumb.title}}</a> &gt; {% endfor %}{{title}}</p>
{% endif %}{% if properties %}
## Properties

<ul>
{% for property in properties %}<li><strong>{% if property.name-link %}<a{% if property.name-link.external? %} class="external" target="_blank" rel="noopener"{% endif %} href="{{property.name-link.url}}">{{property.name}}</a>{% else %}{{property.name}}{% endif %}</strong>: {% if property.link %}<a{% if property.link.external? %} class="external" target="_blank" rel="noopener"{% endif %} href="{{property.link.url}}">{{property.link.title}}</a>{% else %}{{property.value}}{% endif %}</li>
{% endfor %}</ul>
{% endif %}{% if extends %}
## Extends

<ul>
{% for parent in extends %}<li><a{% if parent.external? %} class="external" target="_blank" rel="noopener"{% endif %} href="{{parent.url}}">{{parent.title}}</a></li>
{% endfor %}</ul>
{% endif %}
