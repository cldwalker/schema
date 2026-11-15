Title: {{title}}
Date: {{date}}
Tags: {{tags|join:","}}
{% comment %}
Sections are html blocks so markdown doesn't autolink url values inside <a>.
Html blocks end at a blank line, so <ul> blocks must not contain one.
{% endcomment %}{% if properties %}
## Properties

<ul>
{% for property in properties %}<li><strong>{{property.name}}</strong>: {% if property.link %}<a{% if property.link.external? %} class="external" target="_blank" rel="noopener"{% endif %} href="{{property.link.url}}">{{property.link.title}}</a>{% else %}{{property.value}}{% endif %}</li>
{% endfor %}</ul>
{% endif %}{% if extends %}
## Extends

<ul>
{% for parent in extends %}<li><a{% if parent.external? %} class="external" target="_blank" rel="noopener"{% endif %} href="{{parent.url}}">{{parent.title}}</a></li>
{% endfor %}</ul>
{% endif %}
