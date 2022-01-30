# Example::Testing::WordPress

An example resource that creates a website based on WordPress 5.2.2.

## Syntax

To declare this entity in your AWS CloudFormation template, use the following syntax:

### JSON

<pre>
{
    "Type" : "Example::Testing::WordPress",
    "Properties" : {
        "<a href="#name" title="Name">Name</a>" : <i>String</i>,
        "<a href="#subnetid" title="SubnetId">SubnetId</a>" : <i>String</i>,
    }
}
</pre>

### YAML

<pre>
Type: Example::Testing::WordPress
Properties:
    <a href="#name" title="Name">Name</a>: <i>String</i>
    <a href="#subnetid" title="SubnetId">SubnetId</a>: <i>String</i>
</pre>

## Properties

#### Name

A name associated with the website.

_Required_: Yes

_Type_: String

_Minimum_: <code>1</code>

_Maximum_: <code>219</code>

_Pattern_: <code>^[a-zA-Z0-9]{1,219}\Z</code>

_Update requires_: [No interruption](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/using-cfn-updating-stacks-update-behaviors.html#update-no-interrupt)

#### SubnetId

A subnet in which to host the website.

_Required_: Yes

_Type_: String

_Pattern_: <code>^(subnet-[a-f0-9]{13})|(subnet-[a-f0-9]{8})\Z</code>

_Update requires_: [No interruption](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/using-cfn-updating-stacks-update-behaviors.html#update-no-interrupt)

## Return Values

### Fn::GetAtt

The `Fn::GetAtt` intrinsic function returns a value for a specified attribute of this type. The following are the available attributes and sample return values.

For more information about using the `Fn::GetAtt` intrinsic function, see [Fn::GetAtt](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/intrinsic-function-reference-getatt.html).

#### PublicIp

The public IP for the WordPress site.

#### InstanceId

The ID of the instance that backs the WordPress site.

