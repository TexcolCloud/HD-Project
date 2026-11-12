import type { Model } from "../api/types";

export function messageDestination(
  message: Pick<Model<"ServiceMessage">, "businessType" | "businessId">,
): string | undefined {
  const id = encodeURIComponent(message.businessId);
  switch (message.businessType) {
    case "REPAIR":
      return `/repairs/${id}`;
    case "REMEDIATION":
      return `/remediations?taskId=${id}`;
    case "VISITOR":
      return `/visitors?visitorId=${id}`;
    case "HYGIENE":
      return `/hygiene?inspectionId=${id}`;
    default:
      return undefined;
  }
}
