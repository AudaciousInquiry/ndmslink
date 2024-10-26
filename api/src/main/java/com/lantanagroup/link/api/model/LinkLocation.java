package com.lantanagroup.link.api.model;

import lombok.Getter;
import lombok.Setter;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.codesystems.ContactPointSystem;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Getter
@Setter
public class LinkLocation {
    @NotBlank(message = "Location id must be specified")
    private String id;
    @NotBlank(message = "Location name must be specified")
    private String name;
    @NotBlank(message = "Location phone must be specified")
    private String phone;
    @Valid
    @NotNull(message = "Location Address must be specified")
    private LinkLocationAddress address;
    @Valid
    @NotNull(message = "Location Coordinates must be specified")
    private LinkLocationCoordinates coordinates;

    public Location toFhirLocation() {
        Location location = new Location();
        location.setId(this.id);
        location.addIdentifier(
                new Identifier().setValue(this.id)
        );
        location.setStatus(Location.LocationStatus.ACTIVE);
        location.setName(this.name);
        location.addTelecom(
                new ContactPoint()
                        .setValue(this.phone)
                        .setUse(ContactPoint.ContactPointUse.WORK)
                        .setSystem(ContactPoint.ContactPointSystem.PHONE)
        );
        location.setAddress(this.address.toFhirAddress());
        location.setPosition(this.coordinates.toFhirLocationPositionComponent());
        return location;
    }
}