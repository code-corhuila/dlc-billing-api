"""Actual shell and portal assets on the disposable real-owner test platform."""
import json
import os
import socket
from integration_platform import Platform


class ShellPlatform(Platform):
    def __init__(self):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1",0))
            self.port=listener.getsockname()[1]
        super().__init__("http://localhost:"+str(self.port))

    def start(self):
        super().start()
        self.container("iam-portal",os.environ["IAM_PORTAL_IMAGE"])
        self.container("billing-portal",os.environ["BILLING_PORTAL_IMAGE"])
        deployment=self.directory/"deployment"
        deployment.mkdir()
        (deployment/"auth-config.json").write_text(json.dumps(dict(
            issuer="integration-staff-issuer",audience="integration-staff")))
        def entry(owner,release,navigation):
            return dict(portalId=owner,repository="dlc-"+owner+"-portal",release=release,
                        entryUrl=f"/portals/{owner}/{release}/entry.js",navigation=navigation)
        def nav(identity,label,path):
            return dict(id=identity,label=label,path=path,visibility=dict(rolesAny=[],permissionsAll=[]))
        registry=dict(contractVersion=1,registryRevision=self.prefix,portals=[
            entry("iam","0.1.0-local",[]),entry("billing","2.0.0",[
                nav("procedures","Procedimientos","/app/billing/procedures"),
                nav("rules","Reglas adicionales","/app/billing/procedures/rules")])])
        (deployment/"portal-registry.json").write_text(json.dumps(registry))
        env=dict(GATEWAY_UPSTREAM="http://api-gateway:8080/api/v1",
                 IAM_UPSTREAM="http://iam-portal",BILLING_UPSTREAM="http://billing-portal",
                 PATIENT_UPSTREAM="http://not-deployed",CLINICAL_UPSTREAM="http://not-deployed",
                 APPOINTMENTS_UPSTREAM="http://not-deployed")
        name=self.prefix+"-front"
        self.run("docker","run","-d","--name",name,"--network",self.prefix,
                 "--label","com.dilucca.integration="+self.prefix,"-p",f"127.0.0.1:{self.port}:80",
                 "--mount",f"type=bind,source={deployment},target=/usr/share/nginx/deployment,readonly",
                 *[part for key in env for part in ("-e",key)],os.environ["FRONT_RUNTIME_IMAGE"],env=env)
        self.names.append(name)
        self.base=self.origin
        self.wait(lambda:self.request("GET","/healthz")[0]==200,"Real shell")
